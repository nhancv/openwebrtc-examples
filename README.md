OpenWebRTC example apps
=======================

This repo contains client examples showing how to use OpenWebRTC as well as simple WebRTC web app that can be used for testing.

**Wiki**
* [Developing iOS apps](https://github.com/EricssonResearch/openwebrtc-examples/wiki/Developing-iOS-apps)

**Contents**

STUN SERVER

Some stun server you can use:
```
stun.l.google.com:19302
stun1.l.google.com:19302
stun2.l.google.com:19302
stun3.l.google.com:19302
stun4.l.google.com:19302
stun.ekiga.net
stun.ideasip.com
stun.iptel.org
stun.rixtelecom.se
stun.schlund.de
stunserver.org
stun.softjoys.com
stun.voiparound.com
stun.voipbuster.com
stun.voipstunt.com
stun.bcs2005.net
```

Android
* Native - Uses the C API to show a self-view video. Video is rendered using OpenGL.
* NativeCall - Fully native app that connects to [http://demo.openwebrtc.org](http://demo.openwebrtc.org).
* WebView - Hybrid (native/webview) app that wraps [http://demo.openwebrtc.org](http://demo.openwebrtc.org).
* Note with native call: url server must be https. 
Get certificate by exporting in web browser with format Base-64 encoded X.509 (.CER) and copy to assert folder. Custom SignalingChannel as below:
- CER_FILE_NAME certificate file name (ex: "cert.cer")
- HOST_NAME is host in certificate (in CN field or issued to)

```java
public HttpsURLConnection setUpHttpsConnection(String urlString) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream caInput = new BufferedInputStream(context.getAssets().open(CER_FILE_NAME));
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
                    return hv.verify(HOST_NAME, session);
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
```
Get InputStream
```java
InputStream mEventStream = setUpHttpsConnection(mServerToClientUrl).getInputStream();
BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(mEventStream));
```
Post json information request
```java
JSONObject message;
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
```

Set request camera on android 6. Custom NativeCallExampleActivity
```java
  private static final int REQUEST_CAMERA = 0x01;
  
  @Override
  protected void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      ....
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
```

iOS
* NativeDemo - Fully native app that uses the C API and connects to [http://demo.openwebrtc.org](http://demo.openwebrtc.org).
* SimpleDemo - Hybrid (native/webview) app that wraps [http://demo.openwebrtc.org](http://demo.openwebrtc.org).
* Selfie - Uses the C API to show a self-view video. Video is rendered using OpenGL.

OS X
* Camera Test - Uses the C API to show a self-view video. Video is rendered using OpenGL.

web
* `channel_server.js` - Node.js based server and web application used for [http://demo.openwebrtc.org](http://demo.openwebrtc.org)
* Note run server: fix web sdp (replace file sdp.js from openwebrtc\bridge\client\sdp.js https://github.com/nhancv/ot-openwebrtc)
* Generate self-signed certificated: selfsignedcertificate.com
* Run: ```node channel_server.js 1234 4321 cert/nhancao.key cert/nhancao.cert```
