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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;


public class SignalingChannel {
    public static final String TAG = "EventSource";

//    private final HttpClient mHttpSendClient = new DefaultHttpClient();
    private final Handler mMainHandler;
    private final String mClientToServerUrl;
    private final String mServerToClientUrl;
    private Handler mSendHandler;
    private InputStream mEventStream;
    private Map<String, PeerChannel> mPeerChannels = new HashMap<>();
    private JoinListener mJoinListener;
    private DisconnectListener mDisconnectListener;
    private SessionFullListener mSessionFullListener;
    private Context context;

    public SignalingChannel(Context context, String baseUrl, String session) {
        this.context = context;
        String userId = new BigInteger(40, new Random()).toString(32);
        mServerToClientUrl = baseUrl + "/stoc/" + session + "/" + userId;
        mClientToServerUrl = baseUrl + "/ctos/" + session + "/" + userId;
        mMainHandler = new Handler(Looper.getMainLooper());
        Thread sendThread = new SendThread();
        sendThread.start();
        open();
    }

    public HttpsURLConnection setUpHttpsConnection(String urlString) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream caInput = new BufferedInputStream(context.getAssets().open("nhancao.cert"));
            Certificate ca = cf.generateCertificate(caInput);

            // Create a KeyStore containing our trusted CAs
            String keyStoreType = KeyStore.getDefaultType();
            KeyStore keyStore = KeyStore.getInstance(keyStoreType);
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", ca);

            // Create a TrustManager that trusts the CAs in our KeyStore
            String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(tmfAlgorithm);
            tmf.init(keyStore);

            // Create an SSLContext that uses our TrustManager
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);

            // Tell the URLConnection to use a SocketFactory from our SSLContext
            HostnameVerifier hostnameVerifier = new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    HostnameVerifier hv =
                            HttpsURLConnection.getDefaultHostnameVerifier();
                    return hv.verify("nhancao", session);
                }
            };

            URL url = new URL(urlString);
            HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
            urlConnection.setSSLSocketFactory(context.getSocketFactory());
            urlConnection.setHostnameVerifier(hostnameVerifier);
            return urlConnection;
        } catch (Exception ex) {
            Log.e(TAG, "Failed to establish SSL connection to server: " + ex.toString());
            return null;
        }
    }

    private void open() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mEventStream = setUpHttpsConnection(mServerToClientUrl).getInputStream();
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(mEventStream));
                    readEventStream(bufferedReader);
                } catch (IOException exception) {
                    Log.e(TAG, "SSE: " + exception);
                    exception.printStackTrace();
                } finally {
                    if (mEventStream != null) {
                        try {
                            mEventStream.close();
                        } catch (IOException ignored) {
                        }
                        mEventStream = null;
                    }
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            for (PeerChannel peerChannel : mPeerChannels.values()) {
                                peerChannel.onDisconnect();
                            }
                            if (mDisconnectListener != null) {
                                mDisconnectListener.onDisconnect();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void readEventStream(final BufferedReader bufferedReader) throws IOException {
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            if (line.length() > 1) {
                final String[] eventSplit = line.split(":", 2);
                final StringBuilder data = new StringBuilder();

                if (eventSplit.length != 2 || !eventSplit[0].equals("event")) {
                    Log.w(TAG, "SSE: invalid event: " + line + " => " + Arrays.toString(eventSplit));
                    while (!(line = bufferedReader.readLine()).isEmpty()) {
                        Log.w(TAG, "SSE: skipped after malformed event: " + line);
                    }
                    break;
                }

                final String event = eventSplit[1];

                while ((line = bufferedReader.readLine()) != null) {
                    if (line.length() > 1) {
                        final String[] dataSplit = line.split(":", 2);

                        if (dataSplit.length != 2 || !dataSplit[0].equals("data")) {
                            Log.w(TAG, "SSE: invalid data: " + line + " => " + Arrays.toString(dataSplit));
                        }
                        data.append(dataSplit[1]);
                    } else {
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                handleEvent(event, data.toString());
                            }
                        });
                        break;
                    }
                }
            }
        }
    }

    private void handleEvent(final String event, final String data) {
        if (event.startsWith("user-")) {
            String peer = event.substring(5);
            PeerChannel peerChannel = mPeerChannels.get(peer);
            if (peerChannel != null) {
                peerChannel.onMessage(data);
            }
        } else if (event.equals("join")) {
            PeerChannel peerChannel = new PeerChannel(data);
            mPeerChannels.put(data, peerChannel);
            if (mJoinListener != null) {
                mJoinListener.onPeerJoin(peerChannel);
            }
        } else if (event.equals("leave")) {
            PeerChannel peerChannel = mPeerChannels.remove(data);
            if (peerChannel != null) {
                peerChannel.onDisconnect();
            }
        } else if (event.equals("sessionfull")) {
            if (mSessionFullListener != null) {
                mSessionFullListener.onSessionFull();
            }
        } else {
            Log.w(TAG, "unhandled event: " + event);
        }
    }

    public void setJoinListener(JoinListener joinListener) {
        mJoinListener = joinListener;
    }

    public void setDisconnectListener(DisconnectListener onDisconnectListener) {
        mDisconnectListener = onDisconnectListener;
    }

    public void setSessionFullListener(final SessionFullListener sessionFullListener) {
        mSessionFullListener = sessionFullListener;
    }

    public interface MessageListener {
        public void onMessage(JSONObject data);
    }

    public interface JoinListener {
        public void onPeerJoin(final PeerChannel peerChannel);
    }

    public interface SessionFullListener {
        public void onSessionFull();
    }

    public interface DisconnectListener {
        public void onDisconnect();
    }

    public interface PeerDisconnectListener {
        public void onPeerDisconnect(final PeerChannel peerChannel);
    }

    private class SendThread extends Thread {
        @Override
        public void run() {
            Looper.prepare();
            mSendHandler = new Handler();
            Looper.loop();
            Log.d(TAG, "SendThread: quit");
        }
    }

    public class PeerChannel {
        private final String mPeerId;
        private MessageListener mMessageListener;
        private PeerDisconnectListener mDisconnectListener;
        private boolean mDisconnected = false;

        private PeerChannel(String peerId) {
            mPeerId = peerId;
        }

        public void send(final JSONObject message) {
            if (mDisconnected) {
                Log.w(TAG, "tried to send message to disconnected peer: " + mPeerId);
                return;
            }
            mSendHandler.post(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection urlConnection = setUpHttpsConnection(mClientToServerUrl + "/" + mPeerId);
                    try {
                        urlConnection.setReadTimeout( 10000 /*milliseconds*/ );
                        urlConnection.setConnectTimeout( 15000 /* milliseconds */ );
                        urlConnection.setRequestMethod("POST");
                        urlConnection.setDoInput(true);
                        urlConnection.setDoOutput(true);
                        urlConnection.setFixedLengthStreamingMode(message.toString().getBytes().length);
                        urlConnection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
                        urlConnection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                        urlConnection.connect();
                        OutputStream os = new BufferedOutputStream(urlConnection.getOutputStream());
                        os.write(message.toString().getBytes("UTF-8"));
                        //clean up
                        os.flush();
                        os.close();
                    } catch (IOException exception) {
                        exception.printStackTrace();
                        Log.e(TAG, "failed to send message to " + mPeerId + ": " + exception.toString());
                    } finally {
                        urlConnection.disconnect();
                    }
                }
            });
        }

        private void onMessage(String message) {
            if (mDisconnected) {
                Log.w(TAG, "got message from disconnected peer: " + mPeerId);
                return;
            }
            if (mMessageListener != null) {
                try {
                    JSONObject json = new JSONObject(message);
                    mMessageListener.onMessage(json);
                } catch (JSONException exception) {
                    Log.w(TAG, "failed to decode message: " + exception);
                }
            }
        }

        private void onDisconnect() {
            mDisconnected = true;
            if (mDisconnectListener != null) {
                mDisconnectListener.onPeerDisconnect(this);
                mDisconnectListener = null;
                mMessageListener = null;
            }
        }

        public void setMessageListener(final MessageListener messageListener) {
            mMessageListener = messageListener;
        }

        public void setDisconnectListener(final PeerDisconnectListener onDisconnectListener) {
            mDisconnectListener = onDisconnectListener;
        }

        public String getPeerId() {
            return mPeerId;
        }

        @Override
        public String toString() {
            return "User[" + mPeerId + "]";
        }
    }
}
