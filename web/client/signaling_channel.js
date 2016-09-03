/*
 * Simple signaling channel for WebRTC (use with channel_server.js).
 */

function SignalingChannel(sessionId) {
    if (!sessionId)
        sessionId = location.hash = location.hash.substr(1) || createId();
    userId = createId();
    if (callMode == 1) userId = broadCastId;

    var channels = {};

    var listeners = {
        "onpeer": null,
        "onsessionfull": null
    };
    for (var name in listeners)
        Object.defineProperty(this, name, createEventListenerDescriptor(name, listeners));

    function createId() {
        return Math.random().toString(16).substr(2);
    };

    var es = new EventSource("/stoc/" + sessionId + "/" + userId);

    es.onerror = function () {
        es.close();
    };

    es.addEventListener("join", function (evt) {
        var peerUserId = evt.data;
        console.log("join: " + peerUserId);
        var channel = new PeerChannel(peerUserId);
        channels[peerUserId] = channel;
        peerIds.push(peerUserId);
        if (callMode == 1) {
            activePeerId = broadCastId;
        } else {
            activePeerId = peerUserId;
        }

        es.addEventListener("user-" + peerUserId, userDataHandler, false);
        fireEvent({"type": "peer", "peer": channel}, listeners);
        var peerChannel = peerChannels[peerUserId];
        if (peerChannel == undefined || !peerChannel.hasOwnProperty("peer")) peerChannels[peerUserId] = {
            peer: channel,
            pc: null
        };
        peerChannels[peerUserId]["peer"].onmessage = function (evt) {
            var message = JSON.parse(evt.data);
            if (!peerChannels[peerUserId]["pc"] && (message.sessionDescription || message.candidate)) {
                start(false);
            }
            if (message.sessionDescription) {
                peerChannels[peerUserId]["pc"].setRemoteDescription(new RTCSessionDescription({
                    "sdp": SDP.generate(message.sessionDescription),
                    "type": message.type
                }), function () {
                    // if we received an offer, we need to create an answer
                    if (peerChannels[peerUserId]["pc"].remoteDescription.type == "offer")
                        peerChannels[peerUserId]["pc"].createAnswer(function (answer) {
                            peerChannels[peerUserId]["pc"].setLocalDescription(answer, function () {
                                var sessionDescription = SDP.parse(peerChannels[peerUserId]["pc"].localDescription.sdp);
                                peerChannels[peerUserId]["peer"].send(JSON.stringify({
                                    "sessionDescription": sessionDescription,
                                    "type": peerChannels[peerUserId]["pc"].localDescription.type
                                }, null, 2));
                                // var logMessage = "localDescription set and sent to peer, type: " + peerChannels[peerUserId]["pc"].localDescription.type
                                //     + ", sessionDescription:\n" + JSON.stringify(sessionDescription, null, 2);
                                // console.log(logMessage);
                            }, logError);
                        }, logError);
                }, logError);
            } else if (!isNaN(message.orientation) && remoteView) {
                var transform = "rotate(" + message.orientation + "deg)";
                remoteView.style.transform = remoteView.style.webkitTransform = transform;
            } else {
                var d = message.candidate.candidateDescription;
                message.candidate.candidate = "candidate:" + [
                        d.foundation,
                        d.componentId,
                        d.transport,
                        d.priority,
                        d.address,
                        d.port,
                        "typ",
                        d.type,
                        d.relatedAddress && ("raddr " + d.relatedAddress),
                        d.relatedPort && ("rport " + d.relatedPort),
                        d.tcpType && ("tcptype " + d.tcpType)
                    ].filter(function (x) {
                        return x;
                    }).join(" ");
                peerChannels[peerUserId]["pc"].addIceCandidate(new RTCIceCandidate(message.candidate), function () {
                }, logError);
            }
        };
        peerChannels[peerUserId]["peer"].ondisconnect = function () {
            callButton.disabled = true;
            if (callMode == 1) {
                remoteView.style.visibility = "hidden";
            }
            var pc = peerChannels[peerUserId]["pc"];
            if (pc) {
                pc.close();
            }
            pc = null;
            // Find and remove item from an array
            var i = peerIds.indexOf(peerUserId);
            if (i != -1) {
                peerIds.splice(i, 1);
            }
            delete peerChannels[peerUserId];
        };
    }, false);

    function userDataHandler(evt) {
        var peerUserId = evt.type.substr(5); // discard "user-" part
        var channel = channels[peerUserId];
        if (channel)
            channel.didGetData(evt.data);
    }

    es.addEventListener("leave", function (evt) {
        var peerUserId = evt.data;
        es.removeEventListener("user-" + peerUserId, userDataHandler, false);
        channels[peerUserId].didLeave();
        delete channels[peerUserId];
    }, false);

    es.addEventListener("sessionfull", function () {
        fireEvent({"type": "sessionfull"}, listeners);
        es.close();
    }, false);

    function PeerChannel(peerUserId) {
        var listeners = {
            "onmessage": null,
            "ondisconnect": null
        };
        for (var name in listeners)
            Object.defineProperty(this, name, createEventListenerDescriptor(name, listeners));

        this.didGetData = function (data) {
            fireEvent({"type": "message", "data": data}, listeners);
        };

        this.didLeave = function () {
            fireEvent({"type": "disconnect"}, listeners);
        };

        var sendQueue = [];

        function processSendQueue() {
            var xhr = new XMLHttpRequest();
            xhr.open("POST", "/ctos/" + sessionId + "/" + userId + "/" + peerUserId);
            xhr.setRequestHeader("Content-Type", "text/plain");
            xhr.send(sendQueue[0]);
            xhr.onreadystatechange = function () {
                if (xhr.readyState == xhr.DONE) {
                    sendQueue.shift();
                    if (sendQueue.length > 0)
                        processSendQueue();
                }
            };
        }

        this.send = function (message) {
            if (sendQueue.push(message) == 1)
                processSendQueue();
        };
    }

    function createEventListenerDescriptor(name, listeners) {
        return {
            "get": function () {
                return listeners[name];
            },
            "set": function (cb) {
                listeners[name] = cb instanceof Function ? cb : null;
            },
            "enumerable": true
        };
    }

    function fireEvent(evt, listeners) {
        var listener = listeners["on" + evt.type];
        if (listener)
            listener(evt);
    }
}
