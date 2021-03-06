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
import android.view.Gravity;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
        SignalingChannel.PeerDisconnectListener {
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

    boolean sendAudio, sendVideo, revcAudio, revcVideo;
    private Button mJoinButton;
    private Button mCallButton;
    private EditText mSessionInput;
    private CheckBox mAudioCheckBox;
    private CheckBox mVideoCheckBox;
    private CheckBox mBroadCastCheckBox;
    private CheckBox mConferenceCheckBox;
    private EditText mUrlSetting;
    private LinearLayout vConferenceContainer;
    private HorizontalScrollView vConferenceContainerScrollView;
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
    private Map<String, SimpleStreamSet> streamSets = new HashMap<>();
    private Map<String, VideoView> videoViewMap = new HashMap<>();
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
        TextureView selfView = (TextureView) findViewById(R.id.self_view);
        TextureView remoteView = (TextureView) findViewById(R.id.remote_view);
        selfView.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
        remoteView.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
        if (running) {
            Log.d(TAG, "setting self-view: " + selfView);
            if (mSelfView != null)
                mSelfView.setView(selfView);
            if (mRemoteView != null)
                mRemoteView.setView(remoteView);
            //            mStreamSet.setDeviceOrientation(mWindowManager.getDefaultDisplay().getRotation());
        } else {
            Log.d(TAG, "stopping self-view");
            if (mSelfView != null)
                mSelfView.stop();
            if (mRemoteView != null)
                mRemoteView.stop();
        }

        if (running && callMode == 2) {
            remoteView.setVisibility(View.GONE);
            vConferenceContainerScrollView.setVisibility(View.VISIBLE);
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
        vConferenceContainerScrollView = (HorizontalScrollView) findViewById(R.id.vConferenceContainerScrollView);
        vConferenceContainer = (LinearLayout) findViewById(R.id.vConferenceContainer);

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
        Log.e(TAG, "onSelfViewClicked");
        if (mSelfView != null) {
            mSelfView.setRotation((mSelfView.getRotation() + 1) % 4);
        }
    }

    public void onJoinClicked(final View view) {
        Log.e(TAG, "onJoinClicked");

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
        mBroadCastCheckBox.setEnabled(false);
        mConferenceCheckBox.setEnabled(false);

        mSignalingChannel = new SignalingChannel(NativeCallExampleActivity.this, getUrl(), sessionId, callMode);
        mSignalingChannel.setJoinListener(this);
        mSignalingChannel.setDisconnectListener(this);
        mSignalingChannel.setSessionFullListener(this);

        sendAudio = mAudioCheckBox.isChecked();
        sendVideo = mVideoCheckBox.isChecked();
        revcAudio = !sendAudio || !(callMode == 1);
        revcVideo = !sendVideo || !(callMode == 1);

        mSelfView = CameraSource.getInstance().createVideoView();
        updateVideoView(true);
    }

    @Override
    public void onPeerJoin(final SignalingChannel.PeerChannel peerChannel) {
        Log.e(TAG, "onPeerJoin => " + peerChannel.getPeerId());

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
        rtcSession.setOnLocalCandidateListener(new RtcSession.OnLocalCandidateListener() {
            @Override
            public void onLocalCandidate(RtcCandidate candidate) {
                try {
                    JSONObject json = new JSONObject();
                    json.putOpt("candidate", RtcCandidates.toJsep(candidate));
                    Log.d(TAG, "sending candidate: " + json);
                    peerChannel.send(json);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        rtcSession.setOnLocalDescriptionListener(new RtcSession.OnLocalDescriptionListener() {
            @Override
            public void onLocalDescription(SessionDescription localDescription) {
                try {
                    JSONObject json = SessionDescriptions.toJsep(localDescription);
                    Log.d(TAG, "sending sdp: " + json);
                    peerChannel.send(json);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        rtcSessions.put(peerChannel.getPeerId(), rtcSession);

        SimpleStreamSet streamSet = SimpleStreamSet.defaultConfig(sendAudio, sendVideo, revcAudio, revcVideo);
        streamSets.put(peerChannel.getPeerId(), streamSet);

        //Update call button
        if ((peerIds.indexOf(SignalingChannel.BROADCAST_ID) != -1 && callMode == 1) || peerIds.indexOf(SignalingChannel.BROADCAST_ID) == -1) {
            mCallButton.setEnabled(true);
        }
    }

    @Override
    public void onPeerDisconnect(final SignalingChannel.PeerChannel peerChannel) {
        Log.e(TAG, "onPeerDisconnect => " + peerChannel.getPeerId());
        try {
            if (peerIds.size() < 2) {
                updateVideoView(false);
            }
            if (callMode == 2) {
                videoViewMap.get(peerChannel.getPeerId()).stop();
                videoViewMap.remove(peerChannel.getPeerId());
            }

            rtcSessions.get(peerChannel.getPeerId()).stop();
            streamSets.remove(peerChannel.getPeerId());
            rtcSessions.remove(peerChannel.getPeerId());
            for (int i = 0; i < peerIds.size(); i++) {
                if (peerIds.get(i).equals(peerChannel.getPeerId())) {
                    if (callMode == 2) {
                        vConferenceContainer.removeViewAt(i);
                    }
                    peerIds.remove(i);
                    break;
                }
            }
            peerChannels.remove(peerChannel.getPeerId());

            mSessionInput.setEnabled(true);
            mJoinButton.setEnabled(true);
            mCallButton.setEnabled(false);
            mAudioCheckBox.setEnabled(true);
            mVideoCheckBox.setEnabled(true);
            mBroadCastCheckBox.setEnabled(true);
            mConferenceCheckBox.setEnabled(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                assert sessionDescription != null;
                if (sessionDescription.getType() == SessionDescription.Type.OFFER) {
                    onInboundCall(peerChannel, sessionDescription);
                } else {
                    onAnswer(peerChannel, sessionDescription);
                }
            } catch (InvalidDescriptionException e) {
                e.printStackTrace();
            }
        }
        if (json.has("orientation")) {
//                handleOrientation(json.getInt("orientation"));
        }
    }

    public void onCallClicked(final View view) {
        Log.e(TAG, "onCallClicked");
        if (callMode == 1 || callMode == 2) {
            for (int i = 0; i < peerIds.size(); i++) {
                SimpleStreamSet streamSet = streamSets.get(peerIds.get(i));
                rtcSessions.get(peerIds.get(i)).start(streamSet);
                if (callMode == 2 && !videoViewMap.containsKey(peerIds.get(i))) {
                    VideoView videoView = streamSet.createRemoteView();
                    TextureView textureView = createVideoView();
                    videoView.setView(textureView);
                    videoViewMap.put(peerIds.get(i), videoView);
                }
            }
        } else if (callMode == 0) {
            if (peerIds.indexOf(SignalingChannel.BROADCAST_ID) == -1) {
                SimpleStreamSet streamSet = streamSets.get(activePeerId);
                mRemoteView = streamSet.createRemoteView();
                rtcSessions.get(activePeerId).start(streamSet);
            } else {
                SimpleStreamSet streamSet = streamSets.get(SignalingChannel.BROADCAST_ID);
                mRemoteView = streamSet.createRemoteView();
                rtcSessions.get(SignalingChannel.BROADCAST_ID).start(streamSet);
            }
        }
        mCallButton.setEnabled(false);
        updateVideoView(true);
    }

    public void onRestartClicked(final View view) {
        Log.d(TAG, "onRestartClicked");
        Utils.restartApplication(getApplicationContext());
    }

    private void onInboundCall(final SignalingChannel.PeerChannel peerChannel, final SessionDescription sessionDescription) {
        RtcSession rtcSession = rtcSessions.get(peerChannel.getPeerId());
        try {
            rtcSession.setRemoteDescription(sessionDescription);
            SimpleStreamSet streamSet = streamSets.get(peerChannel.getPeerId());
            if (callMode == 2 && !videoViewMap.containsKey(peerChannel.getPeerId())) {
                VideoView videoView = streamSet.createRemoteView();
                TextureView textureView = createVideoView();
                videoView.setView(textureView);
                videoViewMap.put(peerChannel.getPeerId(), videoView);
            } else {
                mRemoteView = streamSet.createRemoteView();
            }
            rtcSession.start(streamSet);
            updateVideoView(true);
        } catch (InvalidDescriptionException e) {
            e.printStackTrace();
        }
    }

    private void onAnswer(final SignalingChannel.PeerChannel peerChannel, final SessionDescription sessionDescription) {
        RtcSession rtcSession = rtcSessions.get(peerChannel.getPeerId());
        if (rtcSession != null) {
            try {
                rtcSession.setRemoteDescription(sessionDescription);
            } catch (InvalidDescriptionException e) {
                e.printStackTrace();
            }
        }
    }

    private TextureView createVideoView() {
        TextureView textureView = new TextureView(getApplicationContext());
        textureView.setId(textureView.hashCode());
        int size = (int) (240 * Utils.getDensity(getApplicationContext()));
        textureView.setLayoutParams(new LinearLayout.LayoutParams(size, size, Gravity.CENTER));
        vConferenceContainer.addView(textureView);
        return textureView;
    }


    @Override
    public void onDisconnect() {
        Toast.makeText(this, "Disconnected from server", Toast.LENGTH_LONG).show();
        try {
            updateVideoView(false);

            for (int i = 0; i < peerIds.size(); i++) {
                rtcSessions.get(peerIds.get(i)).stop();
                videoViewMap.get(peerIds.get(i)).stop();
            }
            vConferenceContainer.removeAllViews();
            videoViewMap.clear();
            streamSets.clear();
            rtcSessions.clear();
            peerChannels.clear();
            peerIds.clear();
            mSignalingChannel = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
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
