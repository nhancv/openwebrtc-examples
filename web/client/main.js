var isMozilla = window.mozRTCPeerConnection && !window.webkitRTCPeerConnection;
if (isMozilla) {
    window.webkitURL = window.URL;
    navigator.webkitGetUserMedia = navigator.mozGetUserMedia;
    window.webkitRTCPeerConnection = window.mozRTCPeerConnection;
    window.RTCSessionDescription = window.mozRTCSessionDescription;
    window.RTCIceCandidate = window.mozRTCIceCandidate;
}
var broadCastId = "000000000000";
var selfView;
var remoteView;
var callButton;
var audioCheckBox;
var videoCheckBox;
var audioOnlyView;
var signalingChannel;
var localStream;
var chatDiv;
var chatText;
var chatButton;
var chatCheckBox;
var broadcastCheckBox;
var conferenceCheckBox;
var conferenceContainer;
var videoConferenceSize = 300;
var callMode; //0: p2p, 1: broadcast, 2: conference
var peerIds = [];
var peerChannels = {};
var activePeerId;

if (!window.hasOwnProperty("orientation")) {
    // window.orientation = -90;
}

var offerOptions = {
    offerToReceiveAudio: true,
    offerToReceiveVideo: true
};
// must use 'url' here since Firefox doesn't understand 'urls'
var configuration = {
    "iceServers": [
        {
            "url": "stun:stun.ideasip.com"
        },
        {
            "url": "turn:stun.ideasip.com",
            "username": "webrtc",
            "credential": "secret"
        }
    ]
};
window.onload = function () {
    selfView = document.getElementById("self_view");
    remoteView = document.getElementById("remote_view");
    callButton = document.getElementById("call_but");
    var joinButton = document.getElementById("join_but");
    audioCheckBox = document.getElementById("audio_cb");
    videoCheckBox = document.getElementById("video_cb");
    audioOnlyView = document.getElementById("audio-only-container");
    var shareView = document.getElementById("share-container");
    chatText = document.getElementById("chat_txt");
    chatButton = document.getElementById("chat_but");
    chatDiv = document.getElementById("chat_div");
    chatCheckBox = document.getElementById("chat_cb");
    broadcastCheckBox = document.getElementById("broadcast_cb");
    conferenceCheckBox = document.getElementById("conference_cb");
    conferenceContainer = document.getElementById("conference-container");

    // if browser doesn't support DataChannels the chat will be disabled.
    if (webkitRTCPeerConnection.prototype.createDataChannel === undefined) {
        chatCheckBox.checked = false;
        chatCheckBox.disabled = true;
    }

    // Store media preferences
    audioCheckBox.onclick = videoCheckBox.onclick = chatCheckBox.onclick = function (evt) {
        localStorage.setItem(this.id, this.checked);
    };

    audioCheckBox.checked = localStorage.getItem("audio_cb") == "true";
    videoCheckBox.checked = localStorage.getItem("video_cb") == "true";

    if (webkitRTCPeerConnection.prototype.createDataChannel !== undefined)
        chatCheckBox.checked = localStorage.getItem("chat_cb") == "true";

    // Check video box if no preferences exist
    if (!localStorage.getItem("video_cb"))
        videoCheckBox.checked = true;

    joinButton.disabled = !navigator.webkitGetUserMedia;
    joinButton.onclick = function (evt) {
        if (!(audioCheckBox.checked || videoCheckBox.checked || chatCheckBox.checked)) {
            alert("Choose at least audio, video or chat.");
            return;
        }

        callMode = 0;
        if (broadcastCheckBox.checked) {
            callMode = 1;
            offerOptions = {
                offerToReceiveAudio: false,
                offerToReceiveVideo: false
            };
        } else if (conferenceCheckBox.checked) {
            callMode = 2;
            remoteView.style.display = "none";
            conferenceContainer.style.display = "block";
        }

        broadcastCheckBox.disabled = conferenceCheckBox.disabled = audioCheckBox.disabled = videoCheckBox.disabled = chatCheckBox.disabled = joinButton.disabled = true;
        // only chat
        if (!(videoCheckBox.checked || audioCheckBox.checked)) peerJoin();

        // video/audio with our without chat
        if (videoCheckBox.checked || audioCheckBox.checked) {
            // get a local stream
            navigator.webkitGetUserMedia({
                "audio": audioCheckBox.checked,
                "video": videoCheckBox.checked
            }, function (stream) {
                // .. show it in a self-view
                selfView.src = URL.createObjectURL(stream);
                // .. and keep it to be sent later

                if (videoCheckBox.checked)
                    selfView.style.visibility = "visible";
                else if (audioCheckBox.checked && !(chatCheckBox.checked))
                    audioOnlyView.style.visibility = "visible";
                localStream = stream;
                joinButton.disabled = true;
                chatButton.disabled = true;

                peerJoin();
            }, logError);
        }

        function peerJoin() {
            var sessionId = document.getElementById("session_txt").value;
            signalingChannel = new SignalingChannel(sessionId);

            // show and update share link
            var link = document.getElementById("share_link");
            var maybeAddHash = window.location.href.indexOf('#') !== -1 ? "" : ("#" + sessionId);
            link.href = link.textContent = window.location.href + maybeAddHash;
            shareView.style.visibility = "visible";

            callButton.onclick = function () {
                start(true);
            };

            // another peer has joined our session
            signalingChannel.onpeer = function (evt) {
                if ((peerIds.indexOf(broadCastId) != -1 && callMode == 1) ||
                    peerIds.indexOf(broadCastId) == -1) {
                    callButton.disabled = false;
                }
                shareView.style.visibility = "hidden";
            };
        }

    };

    document.getElementById("owr-logo").onclick = function () {
        window.location.assign("http://www.openwebrtc.org");
    };

    var hash = location.hash.substr(1);
    if (hash) {
        document.getElementById("session_txt").value = hash;
        log("Auto-joining session: " + hash);
        joinButton.click();
    } else {
        // set a random session id
        document.getElementById("session_txt").value = Math.random().toString(16).substr(4);
    }
};

function createVideoElement(id) {
    var div = document.createElement("div");
    var att = document.createAttribute("style");
    att.value = "display:inline-block;width:300px;background: white;";
    div.setAttributeNode(att);
    att = document.createAttribute("id");
    att.value = "div-" + id;
    div.setAttributeNode(att);

    var video = document.createElement("video");
    video.autoplay = true;
    video.height = videoConferenceSize;
    video.width = videoConferenceSize;
    att = document.createAttribute("id");
    att.value = id;
    video.setAttributeNode(att);
    div.appendChild(video);

    var title = document.createElement("span");
    title.innerHTML = id;
    div.appendChild(title);

    conferenceContainer.appendChild(div);
    return video;
}

// call start() to initiate
function start(isInitiator) {
    callButton.disabled = true;
    if (callMode == 1 || callMode == 2) {
        for (var i = 0; i < peerIds.length; i++) {
            createPeerConnection(isInitiator, peerIds[i]);
        }
    } else if (callMode == 0) {
        if (peerIds.indexOf(broadCastId) == -1) {
            createPeerConnection(isInitiator, activePeerId);
        } else {
            createPeerConnection(isInitiator, broadCastId);
        }
    }
    // console.log(peerChannels);
}

function createPeerConnection(isInitiator, peerId) {
    var peerChannel = peerChannels[peerId];
    if (peerChannel["pc"] != null) return;
    peerChannel["pc"] = new webkitRTCPeerConnection(configuration);
    // send any ice candidates to the other peer
    peerChannel["pc"].onicecandidate = function (evt) {
        if (evt.candidate) {
            var s = SDP.parse("m=application 0 NONE\r\na=" + evt.candidate.candidate + "\r\n");
            var candidateDescription = s.mediaDescriptions[0].ice.candidates[0];
            peerChannel["peer"].send(JSON.stringify({
                "candidate": {
                    "candidateDescription": candidateDescription,
                    "sdpMLineIndex": evt.candidate.sdpMLineIndex
                }
            }, null, 2));
            // console.log("candidate emitted: " + JSON.stringify(candidateDescription, null, 2));
        }
    };
    // start the chat
    if (chatCheckBox.checked) {
        if (isInitiator) {
            peerChannel["channel"] = peerChannel["pc"].createDataChannel("chat");
            setupChat();
        } else {
            peerChannel["pc"].ondatachannel = function (evt) {
                peerChannel["channel"] = evt.channel;
                setupChat();
            };
        }
    }

    // once the remote stream arrives, show it in the remote video element
    peerChannel["pc"].onaddstream = function (evt) {
        var rmView = remoteView;
        if (callMode == 2) {
            rmView = createVideoElement(peerId);
        }
        rmView.src = URL.createObjectURL(evt.stream);
        if (videoCheckBox.checked) {
            rmView.style.visibility = "visible";
        } else if (audioCheckBox.checked && !(chatCheckBox.checked))
            audioOnlyView.style.visibility = "visible";
        sendOrientationUpdate(peerChannel["peer"]);
    };

    if (audioCheckBox.checked || videoCheckBox.checked) {
        peerChannel["pc"].addStream(localStream);
    }

    if (isInitiator) {
        peerChannel["pc"].createOffer(function (offer) {
            peerChannel["pc"].setLocalDescription(offer, function () {
                var sessionDescription = SDP.parse(peerChannel["pc"].localDescription.sdp);
                peerChannel["peer"].send(JSON.stringify({
                    "sessionDescription": sessionDescription,
                    "type": peerChannel["pc"].localDescription.type
                }, null, 2));
                // var logMessage = "localDescription set and sent to peer, type: " + peerChannel["pc"].localDescription.type
                //     + ", sessionDescription:\n" + JSON.stringify(sessionDescription, null, 2);
                // console.log(logMessage);
            }, logError);
        }, logError, offerOptions);
    }
}

function sendOrientationUpdate(peer) {
    peer.send(JSON.stringify({"orientation": window.orientation + 90}));
}

window.onorientationchange = function () {
    for (var i = 0; i < peerIds.length; i++) {
        if (peerIds[i] != broadCastId) {
            var peer = peerChannels[peerIds[i]]["peer"];
            if (peer) {
                sendOrientationUpdate(peer);
            }
        }
    }

    if (selfView) {
        var transform = "rotate(" + (window.orientation + 90) + "deg)";
        selfView.style.transform = selfView.style.webkitTransform = transform;
    }
};

function logError(error) {
    if (error) {
        if (error.name && error.message)
            log(error.name + ": " + error.message);
        else
            log(error);
    } else
        log("Error (no error message)");
}

function log(msg) {
    log.div = log.div || document.getElementById("log_div");
    log.div.appendChild(document.createTextNode(msg));
    log.div.appendChild(document.createElement("br"));
}

// setup chat
function setupChat() {
    var channel = peerChannels[activePeerId]["channel"];
    channel.onopen = function () {
        chatDiv.style.visibility = "visible";
        chatText.style.visibility = "visible";
        chatButton.style.visibility = "visible";
        chatButton.disabled = false;

        //On enter press - send text message.
        chatText.onkeyup = function (event) {
            if (event.keyCode == 13) {
                chatButton.click();
            }
        };

        chatButton.onclick = function () {
            if (chatText.value) {
                postChatMessage(chatText.value, true);
                channel.send(chatText.value);
                chatText.value = "";
                chatText.placeholder = "";
            }
        };
    };

    // recieve data from remote user
    channel.onmessage = function (evt) {
        postChatMessage(evt.data);
    };

    function postChatMessage(msg, author) {
        var messageNode = document.createElement('div');
        var messageContent = document.createElement('div');
        messageNode.classList.add('chatMessage');
        messageContent.textContent = msg;
        messageNode.appendChild(messageContent);

        if (author) {
            messageNode.classList.add('selfMessage');
        } else {
            messageNode.classList.add('remoteMessage');
        }

        chatDiv.appendChild(messageNode);
        chatDiv.scrollTop = chatDiv.scrollHeight;
    }
}
