/*
 * Copyright (c) 2014, Ericsson AB. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */

package com.ericsson.research.owr.examples.nativecall;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.ericsson.research.owr.Owr;
import com.ericsson.research.owr.sdk.CameraSource;
import com.ericsson.research.owr.sdk.InvalidDescriptionException;
import com.ericsson.research.owr.sdk.RtcCandidate;
import com.ericsson.research.owr.sdk.RtcCandidates;
import com.ericsson.research.owr.sdk.RtcConfig;
import com.ericsson.research.owr.sdk.RtcConfigs;
import com.ericsson.research.owr.sdk.RtcSession;
import com.ericsson.research.owr.sdk.RtcSessions;
import com.ericsson.research.owr.sdk.SessionDescription;
import com.ericsson.research.owr.sdk.SessionDescriptions;
import com.ericsson.research.owr.sdk.SimpleStreamSet;
import com.ericsson.research.owr.sdk.VideoView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NativeCallExampleActivity extends Activity implements
        SignalingChannel.JoinListener,
        SignalingChannel.DisconnectListener,
        SignalingChannel.SessionFullListener,
        SignalingChannel.MessageListener,
        SignalingChannel.PeerDisconnectListener,
        RtcSession.OnLocalCandidateListener,
        RtcSession.OnLocalDescriptionListener {
    private static final String TAG = "NativeCall";

    private static final String PREFERENCE_KEY_SERVER_URL = "url";
    private static final int SETTINGS_ANIMATION_DURATION = 400;
    private static final int SETTINGS_ANIMATION_ANGLE = 90;
    private static final int REQUEST_CAMERA = 0x01;

    /**
     * Initialize OpenWebRTC at startup
     */
    static {
        Log.d(TAG, "Initializing OpenWebRTC");
        Owr.init();
        Owr.runInBackground();
    }

    private Button mJoinButton;
    private Button mCallButton;
    private EditText mSessionInput;
    private CheckBox mAudioCheckBox;
    private CheckBox mVideoCheckBox;
    private CheckBox mBroadCastCheckBox;
    private CheckBox mConferenceCheckBox;
    private EditText mUrlSetting;
    private View mHeader;
    private View mSettingsHeader;

    private int callMode;//0: p2p; 1:broadcast, 2: conference
    private SignalingChannel mSignalingChannel;
    private InputMethodManager mInputMethodManager;
    private WindowManager mWindowManager;

    private Map<String, SignalingChannel.PeerChannel> peerChannels = new HashMap<>();
    private List<String> peerIds = new ArrayList<>();
    private String activePeerId;
    private Map<String, RtcSession> rtcSessions = new HashMap<>();
    private Map<String, SimpleStreamSet> mStreamSets = new HashMap<>();

    private SimpleStreamSet mStreamSet;
    private VideoView mSelfView;
    private VideoView mRemoteView;
    private RtcConfig mRtcConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        initUi();

        mInputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mRtcConfig = RtcConfigs.defaultConfig(Config.STUN_SERVER);
        checkCameraPermission();
    }

    /**
     * Method to check permission
     */
    void checkCameraPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // Camera permission has not been granted.
            requestCameraPermission();
        }
    }

    /**
     * Method to request permission for camera
     */
    private void requestCameraPermission() {
        // Camera permission has not been granted yet. Request it directly.
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA},
                REQUEST_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CAMERA) {
            // BEGIN_INCLUDE(permission_result)
            // Received permission result for camera permission.
            Log.i(TAG, "Received response for Camera permission request.");

            // Check if the only required permission has been granted
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Camera permission has been granted, preview can be displayed
            } else {
                //Permission not granted
                Toast.makeText(NativeCallExampleActivity.this, "You need to grant camera permission to use camera", Toast.LENGTH_LONG).show();
            }

        }
    }

    @Override
    public void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initUi();
        updateVideoView(true);
    }

    private void updateVideoView(boolean running) {
        if (mStreamSet != null) {
            TextureView selfView = (TextureView) findViewById(R.id.self_view);
            TextureView remoteView = (TextureView) findViewById(R.id.remote_view);
            selfView.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
            remoteView.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
            if (running) {
                Log.d(TAG, "setting self-view: " + selfView);
                mSelfView.setView(selfView);
                mRemoteView.setView(remoteView);
                //            mStreamSet.setDeviceOrientation(mWindowManager.getDefaultDisplay().getRotation());
            } else {
                Log.d(TAG, "stopping self-view");
                mSelfView.stop();
                mRemoteView.stop();
            }
        }
    }

    public void initUi() {
        setContentView(R.layout.activity_openwebrtc);

        mCallButton = (Button) findViewById(R.id.call);
        mJoinButton = (Button) findViewById(R.id.join);
        mSessionInput = (EditText) findViewById(R.id.session_id);
        mAudioCheckBox = (CheckBox) findViewById(R.id.audio);
        mVideoCheckBox = (CheckBox) findViewById(R.id.video);
        mBroadCastCheckBox = (CheckBox) findViewById(R.id.cbBroadcast);
        mConferenceCheckBox = (CheckBox) findViewById(R.id.cbConference);

        mJoinButton.setEnabled(true);

        mHeader = findViewById(R.id.header);
        mHeader.setCameraDistance(getResources().getDisplayMetrics().widthPixels * 5);
        mHeader.setPivotX(getResources().getDisplayMetrics().widthPixels / 2);
        mHeader.setPivotY(0);
        mSettingsHeader = findViewById(R.id.settings_header);
        mSettingsHeader.setCameraDistance(getResources().getDisplayMetrics().widthPixels * 5);
        mSettingsHeader.setPivotX(getResources().getDisplayMetrics().widthPixels / 2);
        mSettingsHeader.setPivotY(0);

        mUrlSetting = (EditText) findViewById(R.id.url_setting);
        mUrlSetting.setText(getUrl());
        mUrlSetting.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(final TextView view, final int actionId, final KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideSettings();
                    String url = view.getText().toString();
                    saveUrl(url);
                    return true;
                }
                return false;
            }
        });
    }

    public void onSelfViewClicked(final View view) {
        Log.d(TAG, "onSelfViewClicked");
        if (mStreamSet != null) {
            if (mSelfView != null) {
                mSelfView.setRotation((mSelfView.getRotation() + 1) % 4);
            }
        }
//        mStreamSet.toggleCamera();
    }

    public void onJoinClicked(final View view) {
        Log.d(TAG, "onJoinClicked");

        //Set up call mode
        callMode = 0;
        if (mBroadCastCheckBox.isChecked()) {
            callMode = 1;
        } else if (mConferenceCheckBox.isChecked()) {
            callMode = 2;
        }

        String sessionId = mSessionInput.getText().toString();
        if (sessionId.isEmpty()) {
            mSessionInput.requestFocus();
            mInputMethodManager.showSoftInput(mSessionInput, InputMethodManager.SHOW_IMPLICIT);
            return;
        }

        mInputMethodManager.hideSoftInputFromWindow(mSessionInput.getWindowToken(), 0);
        mSessionInput.setEnabled(false);
        mJoinButton.setEnabled(false);
        mAudioCheckBox.setEnabled(false);
        mVideoCheckBox.setEnabled(false);

        mSignalingChannel = new SignalingChannel(NativeCallExampleActivity.this, getUrl(), sessionId, callMode);
        mSignalingChannel.setJoinListener(this);
        mSignalingChannel.setDisconnectListener(this);
        mSignalingChannel.setSessionFullListener(this);

        boolean sendAudio = mAudioCheckBox.isChecked();
        boolean sendVideo = mVideoCheckBox.isChecked();
        boolean revcAudio = !sendAudio || !(callMode == 1);
        boolean revcVideo = !sendVideo || !(callMode == 1);
        mStreamSet = SimpleStreamSet.defaultConfig(sendAudio, sendVideo, revcAudio, revcVideo);

        mSelfView = CameraSource.getInstance().createVideoView();
        mRemoteView = mStreamSet.createRemoteView();
        updateVideoView(true);
    }

    @Override
    public void onPeerJoin(final SignalingChannel.PeerChannel peerChannel) {
        Log.v(TAG, "onPeerJoin => " + peerChannel.getPeerId());
        mCallButton.setEnabled(true);

        peerChannel.setDisconnectListener(this);
        peerChannel.setMessageListener(this);
        peerIds.add(peerChannel.getPeerId());
        peerChannels.put(peerChannel.getPeerId(), peerChannel);
        if (callMode == 1) {
            activePeerId = SignalingChannel.BROADCAST_ID;
        } else {
            activePeerId = peerChannel.getPeerId();
        }
        RtcSession rtcSession = RtcSessions.create(mRtcConfig);
        rtcSession.setOnLocalCandidateListener(this);
        rtcSession.setOnLocalDescriptionListener(this);
        rtcSessions.put(peerChannel.getPeerId(), rtcSession);

    }

    @Override
    public void onPeerDisconnect(final SignalingChannel.PeerChannel peerChannel) {
        Log.d(TAG, "onPeerDisconnect => " + peerChannel.getPeerId());
        rtcSessions.get(peerChannel.getPeerId()).stop();
        rtcSessions.remove(peerChannel.getPeerId());
        for (int i = 0; i < peerIds.size(); i++) {
            if (peerIds.get(i).equals(peerChannel.getPeerId())) {
                peerIds.remove(i);
                break;
            }
        }
        peerChannels.remove(peerChannel.getPeerId());

        updateVideoView(false);
        mSessionInput.setEnabled(true);
        mJoinButton.setEnabled(true);
        mCallButton.setEnabled(false);
        mAudioCheckBox.setEnabled(true);
        mVideoCheckBox.setEnabled(true);
    }

    @Override
    public synchronized void onMessage(final SignalingChannel.PeerChannel peerChannel, final JSONObject json) {
        if (json.has("candidate")) {
            JSONObject candidate = json.optJSONObject("candidate");
            Log.v(TAG, "candidate: " + candidate);
            RtcCandidate rtcCandidate = RtcCandidates.fromJsep(candidate);
            if (rtcCandidate != null) {
                rtcSessions.get(peerChannel.getPeerId()).addRemoteCandidate(rtcCandidate);
            } else {
                Log.w(TAG, "invalid candidate: " + candidate);
            }
        }
        if (json.has("sdp") || json.has("sessionDescription")) {
            Log.v(TAG, "sdp: " + json);
            try {
                SessionDescription sessionDescription = SessionDescriptions.fromJsep(json);
                if (sessionDescription.getType() == SessionDescription.Type.OFFER) {
                    onInboundCall(sessionDescription);
                } else {
                    onAnswer(sessionDescription);
                }
            } catch (InvalidDescriptionException e) {
                e.printStackTrace();
            }
        }
        if (json.has("orientation")) {
//                handleOrientation(json.getInt("orientation"));
        }
    }

    @Override
    public void onLocalCandidate(final RtcCandidate candidate) {
        for (int i = 0; i < peerIds.size(); i++) {
            SignalingChannel.PeerChannel peerChannel = peerChannels.get(peerIds.get(i));
            if (peerChannel != null) {
                try {
                    JSONObject json = new JSONObject();
                    json.putOpt("candidate", RtcCandidates.toJsep(candidate));
                    Log.d(TAG, "sending candidate: " + json);
                    peerChannel.send(json);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void onCallClicked(final View view) {
        Log.d(TAG, "onCallClicked");
        if (callMode == 1 || callMode == 2) {
            for (int i = 0; i < peerIds.size(); i++) {
                rtcSessions.get(peerIds.get(i)).start(mStreamSet);
            }
        } else if (callMode == 0) {
            if (peerIds.indexOf(SignalingChannel.BROADCAST_ID) == -1) {
                rtcSessions.get(activePeerId).start(mStreamSet);
            } else {
                rtcSessions.get(SignalingChannel.BROADCAST_ID).start(mStreamSet);
            }
        }
        mCallButton.setEnabled(false);
    }

    private void onInboundCall(final SessionDescription sessionDescription) {
        for (int i = 0; i < peerIds.size(); i++) {
            RtcSession rtcSession = rtcSessions.get(peerIds.get(i));
            try {
                rtcSession.setRemoteDescription(sessionDescription);
                rtcSession.start(mStreamSet);
            } catch (InvalidDescriptionException e) {
                e.printStackTrace();
            }
        }
    }

    private void onAnswer(final SessionDescription sessionDescription) {
        for (int i = 0; i < peerIds.size(); i++) {
            RtcSession rtcSession = rtcSessions.get(peerIds.get(i));
            if (rtcSession != null) {
                try {
                    rtcSession.setRemoteDescription(sessionDescription);
                } catch (InvalidDescriptionException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onLocalDescription(final SessionDescription localDescription) {
        for (int i = 0; i < peerIds.size(); i++) {
            SignalingChannel.PeerChannel peerChannel = peerChannels.get(peerIds.get(i));
            if (peerChannel != null) {
                JSONObject json = SessionDescriptions.toJsep(localDescription);
                Log.d(TAG, "sending sdp: " + json);
                peerChannel.send(json);
            }
        }
    }

    @Override
    public void onDisconnect() {
        Toast.makeText(this, "Disconnected from server", Toast.LENGTH_LONG).show();
        updateVideoView(false);
        mStreamSet = null;

        for (int i = 0; i < peerIds.size(); i++) {
            RtcSession rtcSession = rtcSessions.get(peerIds.get(i));
            if (rtcSession != null) {
                rtcSession.stop();
            }
        }
        rtcSessions.clear();
        mSignalingChannel = null;
    }

    @Override
    public void onSessionFull() {
        Toast.makeText(this, "Session is full", Toast.LENGTH_LONG).show();
        mJoinButton.setEnabled(true);
    }

    public void onSettingsClicked(final View view) {
        showSettings();
    }

    public void onCancelSettingsClicked(final View view) {
        hideSettings();
    }

    private void showSettings() {
        mUrlSetting.requestFocus();
        mInputMethodManager.showSoftInput(mUrlSetting, InputMethodManager.SHOW_IMPLICIT);
        mSettingsHeader.setVisibility(View.VISIBLE);
        mSettingsHeader.setRotationX(SETTINGS_ANIMATION_ANGLE);
        mSettingsHeader.animate().rotationX(0).setDuration(SETTINGS_ANIMATION_DURATION).start();
        mHeader.setVisibility(View.VISIBLE);
        mHeader.animate()
                .rotationX(-SETTINGS_ANIMATION_ANGLE)
                .setDuration(SETTINGS_ANIMATION_DURATION)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mHeader.setVisibility(View.INVISIBLE);
                    }
                }).start();
    }

    private void hideSettings() {
        mInputMethodManager.hideSoftInputFromWindow(mUrlSetting.getWindowToken(), 0);
        mHeader.setVisibility(View.VISIBLE);
        mHeader.setRotationX(SETTINGS_ANIMATION_ANGLE);
        mHeader.animate().rotationX(0).setDuration(SETTINGS_ANIMATION_DURATION).start();
        mSettingsHeader.setVisibility(View.VISIBLE);
        mSettingsHeader.animate()
                .rotationX(-SETTINGS_ANIMATION_ANGLE)
                .setDuration(SETTINGS_ANIMATION_DURATION)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mSettingsHeader.setVisibility(View.INVISIBLE);
                    }
                }).start();
    }

    private void saveUrl(final String url) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(PREFERENCE_KEY_SERVER_URL, url).commit();
    }

    private String getUrl() {
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PREFERENCE_KEY_SERVER_URL, Config.DEFAULT_SERVER_ADDRESS);
    }

    /**
     * Shutdown the process as a workaround until cleanup has been fully implemented.
     */
    @Override
    protected void onStop() {
        finish();
        System.exit(0);
    }
}
