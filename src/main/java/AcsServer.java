// Beggining of AcsServer.java //

import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import modules.LoginSession;
import org.json.JSONObject;
import org.json.JSONException;


public class AcsServer {
    // Port for listening to AcqServer
    private static final int ACS_LISTEN_PORT = 5050;
    // Port for the validation web interface (Javalin)
    private static final int ACS_WEB_PORT = 7071;
    // Base URL for the validation links (adjust host/port as needed)
    private static final String VALIDATION_BASE_URL = Config.BASE_URL + ":" + ACS_WEB_PORT; // Use HTTPS in production

    // Keystore for ACS's identity (private key)
    private static final String KEYSTORE_PATH = "./keystore/acsKeystore.jks";
    // Truststore for verifying ACQ's certificate (if client auth is needed on ACS listener)
    private static final String TRUSTSTORE_PATH = "./truststore/acsTruststore.jks";
    private static final String PASSWORD = "password";

    // Hardcoded customer data (for Step 5 validation)
    private static final String VALID_CARD_NUMBER = "1234123412341234";
    private static final String VALID_CVV = "123";
    private static final String VALID_MONTH = "12";
    private static final String VALID_YEAR = "2025";
    private static final String VALID_NAME = "Joe";

    // In-memory store for pending transactions (Token A -> Status) - simplified
    private static final Map<String, String> pendingTransactions = new ConcurrentHashMap<>(); // Use ConcurrentHashMap for thread safety

    // URL for the Merchant Backend callback (Step 10) - ** CHANGE THIS **
    private static final String MERCHANT_CALLBACK_URL = "https://api-achat.makeitnextgen.com/api/order/callback"; // Example URL // https://api-achat.makeitnextgen.com/

    // HttpClient for making the callback (Step 10)
    private static final HttpClient httpClient = HttpClient.newBuilder().build();
    // Store login state AND attempts per tokenA
    private static final Map<String, LoginSession> loginStatus = new ConcurrentHashMap<>();



    public static void main(String[] args) throws Exception {
        // Load ACS KeyStore (identity)
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(new FileInputStream(KEYSTORE_PATH), PASSWORD.toCharArray());

        // Load ACS TrustStore (trusted certs, e.g., ACQ's if needed)
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(new FileInputStream(TRUSTSTORE_PATH), PASSWORD.toCharArray());

        // KeyManagerFactory uses ACS's KeyStore for server-side SSL (listener and web)
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, PASSWORD.toCharArray());

        // TrustManagerFactory uses ACS's TrustStore (e.g., to verify ACQ if client auth is enabled)
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        // SSLContext for the listener socket
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

        // --- Start ACQ Listener Thread ---
        Thread acqListenerThread = new Thread(() -> {
            try {
                startAcqListener(sslContext);
            } catch (Exception e) {
                System.err.println("ACS Server ACQ Listener failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
        acqListenerThread.start();

        // --- Start Javalin Web Server Thread (for validation link) ---
        Thread apiThread = new Thread(() -> {
            try {
                // Note: Javalin setup here uses HTTP. Configure for HTTPS for production.
                startValidationWebServer();
            } catch (Exception e) {
                System.err.println("ACS Server Validation Web Server failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
        apiThread.start();
    }

    // Listens for connections from AcqServer (Step 4)
    private static void startAcqListener(SSLContext sslContext) throws IOException {
        SSLServerSocketFactory serverSocketFactory = sslContext.getServerSocketFactory();
        try (SSLServerSocket serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket(ACS_LISTEN_PORT)) {
            // Optional: Require client auth if ACQ must present its certificate
            // serverSocket.setNeedClientAuth(true);
            System.out.println("ACS Server listening for ACQ on port " + ACS_LISTEN_PORT);

            while (true) {
                try (SSLSocket acqClientSocket = (SSLSocket) serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(acqClientSocket.getInputStream()));
                     BufferedWriter out = new BufferedWriter(new OutputStreamWriter(acqClientSocket.getOutputStream()))) {

                    System.out.println("ACS Server: Connection received from ACQ: " + acqClientSocket.getInetAddress());
                    String requestJson = in.readLine(); // Read JSON from AcqServer
                    System.out.println("ACS Server: Received data from ACQ: " + requestJson);

                    if (requestJson == null || requestJson.isEmpty()) {
                        System.err.println("ACS Server: Received empty request from ACQ.");
                        continue;
                    }

                    String validationLink = "ERROR: ACS Processing failed"; // Default
                    try {
                        JSONObject paymentData = new JSONObject(requestJson);
                        String cardNumber = paymentData.optString("cardNumber", "");
                        String cvv = paymentData.optString("cvv", "");
                        String month = paymentData.optString("month", "");
                        String year = paymentData.optString("year", "");
                        String clientName = paymentData.optString("clientName", "");
                        String tokenA = paymentData.optString("tokenA", "");

                        if (tokenA.isEmpty()) {
                            validationLink = "ERROR: Missing tokenA";
                        } else {
                            // Step 5: Validate card number, cvv, month and year against known customers
                            if (VALID_CARD_NUMBER.equals(cardNumber) && VALID_CVV.equals(cvv) && VALID_MONTH.equals(month) && VALID_YEAR.equals(year) && VALID_NAME.equals(clientName)) {
                                System.out.println("ACS Server: Card number " + cardNumber + " is valid. Generating success link.");
                                // Store token as pending validation
                                pendingTransactions.put(tokenA, "PENDING");
                                // Generate link for successful validation path
                                validationLink = VALIDATION_BASE_URL + "/validate-payment?tokenA=" + URLEncoder.encode(tokenA, StandardCharsets.UTF_8);
                            } else {
                                // Card number doesn't match a known client
                                System.out.println("ACS Server: Card number " + cardNumber + " is UNKNOWN. Generating failure link and logging.");
                                // Log the attempt (simple console log here)
                                System.err.println("LOG: Attempted payment with unknown card: " + cardNumber + ", Token: " + tokenA);
                                // Generate a link that leads directly to a failure indication
                                // Include tokenA so failure page can potentially reference it
                                validationLink = VALIDATION_BASE_URL + "/payment-failed?reason=unknown_card&tokenA=" + URLEncoder.encode(tokenA, StandardCharsets.UTF_8);
                                // Do NOT store this token in pendingTransactions as it's already failed.
                            }
                        }
                    } catch (JSONException e) {
                        System.err.println("ACS Server: Invalid JSON from ACQ: " + requestJson + " - Error: " + e.getMessage());
                        validationLink = "ERROR: Invalid data format from ACQ";
                    } catch (Exception e) {
                        System.err.println("ACS Server: Error processing ACQ request: " + e.getMessage());
                        e.printStackTrace();
                        validationLink = "ERROR: Internal ACS error";
                    }

                    // Step 5: Send the generated validation link (or error) back to AcqServer
                    System.out.println("ACS Server: Sending response back to ACQ: " + validationLink);
                    out.write(validationLink + "\n");
                    out.flush();

                } catch (IOException e) {
                    System.err.println("ACS Server: Error handling ACQ connection: " + e.getMessage());
                    // Continue listening
                } catch (Exception e) {
                    System.err.println("ACS Server: Unexpected error in listener loop: " + e.getMessage());
                    e.printStackTrace();
                }
            } // end while
        } // end try-with-resources (serverSocket)
    }


    // Starts the Javalin Web Server for handling the validation link (Steps 8, 9)
    public static void startValidationWebServer() {
        Javalin app = Javalin.create().start(ACS_WEB_PORT);
        System.out.println("ACS Server Validation Web Server listening on " + Config.BASE_URL + ":" + ACS_WEB_PORT);

        // Endpoint for the user to land on from the validation link (valid card case)
        app.get("/validate-payment", handleValidationPage);

        // Endpoint to handle the user's confirmation action
        app.post("/confirm-payment", handlePaymentConfirmation); // Changed to POST

        // Endpoint for the link generated for unknown cards
        app.get("/payment-failed", handleFailedPage);

        app.get("/bank-login", handleLoginGet);

        app.post("/bank-login", handleLoginSubmit);
    }

    // Handler for Step 9: Display validation page/options to the user
    private static Handler handleValidationPage = ctx -> {
        String tokenA = ctx.queryParam("tokenA");

        // Retrieve login session info
        LoginSession session = loginStatus.get(tokenA);
        // If not logged in, redirect to login
        if (session == null || !session.isLoggedIn) {
            ctx.redirect("/bank-login?tokenA=" + URLEncoder.encode(tokenA, StandardCharsets.UTF_8));
            return;
        }

        // Basic validation for the token
        if (tokenA == null || !pendingTransactions.containsKey(tokenA) || !"PENDING".equals(pendingTransactions.get(tokenA)) || session.attempts >= 3 ) {
            ctx.status(400).html("<h1>Invalid or Expired Payment Request</h1>");
            return;
        }

        // User is authenticated — show the payment confirmation form
        ctx.html("""
<!DOCTYPE html>
<html lang="en">

<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Bank Application</title>
  <style>
    body {
      font-family: Arial, sans-serif;
      margin: 0;
      padding: 0;
      background-color: #F5F5F5;
      color: #0A2463;
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100vh;
    }

    h1 {
      color: #0A2463;
      text-align: center;
      margin-bottom: 20px;
    }

    p {
      text-align: center;
      margin-bottom: 20px;
    }

    a {
      color: #00BFA5;
      text-decoration: none;
      font-weight: bold;
    }

    a:hover {
      text-decoration: underline;
    }

    form {
      background-color: #FFFFFF;
      padding: 20px;
      border-radius: 8px;
      box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
      width: 300px;
      text-align: center;
    }

    label {
      display: block;
      margin-bottom: 10px;
      text-align: left;
    }

    input[type="text"],
    input[type="password"] {
      width: 100%%;
      padding: 10px;
      margin-top: 5px;
      margin-bottom: 15px;
      border: 1px solid #0A2463;
      border-radius: 4px;
      box-sizing: border-box;
    }

    button {
      background-color: #00BFA5;

      color: white;
      border: none;
      padding: 10px 20px;
      border-radius: 4px;
      cursor: pointer;
      font-size: 16px;
      transition: background-color 0.3s ease;
    }

    button:hover {
      background-color: #009688;

    }

    .error {
      color: #FF5252;

      text-align: center;
    }
  </style>
</head>

<body>
  <!-- Confirm Payment Page -->
  <div id="confirm-payment" style="display: flex;flex-direction: column;">
    <div>
      <h1>Confirm Payment</h1>
      <p>Please confirm the transaction associated with request token: %s</p>

    </div>
    <div style="display: flex; align-content: center; justify-content: center;">
      <form action="/confirm-payment" method="post">
        <input type="hidden" name="tokenA" value="%s">
        <div style="display: flex;">
          <button style="margin: 20px;" type="submit" name="action" value="confirm">Confirm Payment</button>
          <button style="margin: 20px;" type="submit" name="action" value="cancel">Cancel Payment</button>

        </div>
      </form>
    </div>
  </div>
  </div>
</body>

</html>
    """.formatted(tokenA, tokenA));
    };

    // Handler for Step 9: User confirms/cancels payment
    // Then triggers Step 10: Callback to Merchant Backend
    private static Handler handlePaymentConfirmation = ctx -> {
        String tokenA = ctx.formParam("tokenA");
        String action = ctx.formParam("action"); // "confirm" or "cancel"

        if (tokenA == null || action == null || !pendingTransactions.containsKey(tokenA) || !"PENDING".equals(pendingTransactions.get(tokenA))) {
            ctx.status(400).html("""
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <title>Bank Application</title>
                    <style>
                      body {
                        font-family: Arial, sans-serif;
                        margin: 0;
                        padding: 0;
                        background-color: #F5F5F5;
                        color: #0A2463;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                      }
                      h1 {
                        color: #0A2463;
                        text-align: center;
                        margin-bottom: 20px;
                      }
                      p {
                        text-align: center;
                        margin-bottom: 20px;
                      }
                      a {
                        color: #00BFA5;
                        text-decoration: none;
                        font-weight: bold;
                      }
                      a:hover {
                        text-decoration: underline;
                      }
                      form {
                        background-color: #FFFFFF;
                        padding: 20px;
                        border-radius: 8px;
                        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                        width: 300px;
                        text-align: center;
                      }
                      label {
                        display: block;
                        margin-bottom: 10px;
                        text-align: left;
                      }
                      input[type="text"],
                      input[type="password"] {
                        width: 100%%;
                        padding: 10px;
                        margin-top: 5px;
                        margin-bottom: 15px;
                        border: 1px solid #0A2463;
                        border-radius: 4px;
                        box-sizing: border-box;
                      }
                      button {
                        background-color: #00BFA5;
                    
                        color: white;
                        border: none;
                        padding: 10px 20px;
                        border-radius: 4px;
                        cursor: pointer;
                        font-size: 16px;
                        transition: background-color 0.3s ease;
                      }
                      button:hover {
                        background-color: #009688;
                    
                      }
                      .error {
                        color: #FF5252;
                    
                        text-align: center;
                      }
                    </style>
                    </head>
                    <body>
                    
                      <!-- Invalid Action or Expired Request Page -->
                      <div id="invalid-action">
                        <h1 class="error">Invalid Action or Expired Request</h1>
                      </div>
                    
                    
                      </div>
                    </body>
                    </html>
                    """);
            return;
        }

        boolean isSuccessful = "confirm".equalsIgnoreCase(action);

        // Update status locally (optional, callback is key)
        pendingTransactions.put(tokenA, isSuccessful ? "CONFIRMED" : "CANCELLED");

        // Remove login status for the token
        loginStatus.remove(tokenA);

        // Step 10: Trigger callback to Merchant Backend
        System.out.println("ACS Server: Performing callback for TokenA: " + tokenA + ", Success: " + isSuccessful);
        boolean callbackSent = sendCallbackToMerchant(tokenA, isSuccessful);

        if (callbackSent) {
            System.out.println("ACS Server: Callback successful.");
            ctx.html(isSuccessful ? """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <title>Bank Application</title>
                    <style>
                      body {
                        font-family: Arial, sans-serif;
                        margin: 0;
                        padding: 0;
                        background-color: #F5F5F5;
                        color: #0A2463;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                      }
                      h1 {
                        color: #0A2463;
                        text-align: center;
                        margin-bottom: 20px;
                      }
                      p {
                        text-align: center;
                        margin-bottom: 20px;
                      }
                      a {
                        color: #00BFA5;
                        text-decoration: none;
                        font-weight: bold;
                      }
                      a:hover {
                        text-decoration: underline;
                      }
                      form {
                        background-color: #FFFFFF;
                        padding: 20px;
                        border-radius: 8px;
                        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                        width: 300px;
                        text-align: center;
                      }
                      label {
                        display: block;
                        margin-bottom: 10px;
                        text-align: left;
                      }
                      input[type="text"],
                      input[type="password"] {
                        width: 100%%;
                        padding: 10px;
                        margin-top: 5px;
                        margin-bottom: 15px;
                        border: 1px solid #0A2463;
                        border-radius: 4px;
                        box-sizing: border-box;
                      }
                      button {
                        background-color: #00BFA5;
                    
                        color: white;
                        border: none;
                        padding: 10px 20px;
                        border-radius: 4px;
                        cursor: pointer;
                        font-size: 16px;
                        transition: background-color 0.3s ease;
                      }
                      button:hover {
                        background-color: #009688;
                    
                      }
                      .error {
                        color: #FF5252;
                    
                        text-align: center;
                      }
                    </style>
                    </head>
                    <body>
                    
                      <!-- Payment Confirmed Page -->
                      <div id="payment-confirmed">
                        <h1>Payment Confirmed</h1>
                        <p>Thank you.</p>
                      </div>
                    
                    
                      </div>
                    </body>
                    </html>
        """ : """
        
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Bank Application</title>
<style>
  body {
    font-family: Arial, sans-serif;
    margin: 0;
    padding: 0;
    background-color: #F5F5F5;
    color: #0A2463;
    display: flex;
    justify-content: center;
    align-items: center;
    height: 100vh;
  }
  h1 {
    color: #0A2463;
    text-align: center;
    margin-bottom: 20px;
  }
  p {
    text-align: center;
    margin-bottom: 20px;
  }
  a {
    color: #00BFA5;
    text-decoration: none;
    font-weight: bold;
  }
  a:hover {
    text-decoration: underline;
  }
  form {
    background-color: #FFFFFF;
    padding: 20px;
    border-radius: 8px;
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
    width: 300px;
    text-align: center;
  }
  label {
    display: block;
    margin-bottom: 10px;
    text-align: left;
  }
  input[type="text"],
  input[type="password"] {
    width: 100%%;
    padding: 10px;
    margin-top: 5px;
    margin-bottom: 15px;
    border: 1px solid #0A2463;
    border-radius: 4px;
    box-sizing: border-box;
  }
  button {
    background-color: #00BFA5;

    color: white;
    border: none;
    padding: 10px 20px;
    border-radius: 4px;
    cursor: pointer;
    font-size: 16px;
    transition: background-color 0.3s ease;
  }
  button:hover {
    background-color: #009688;

  }
  .error {
    color: #FF5252;

    text-align: center;
  }
</style>
</head>
<body>

  <!-- Payment Cancelled Page -->
  <div id="payment-cancelled">
    <h1>Payment Cancelled</h1>
    <p>Transaction was cancelled.</p>
  </div>



  </div>
</body>
</html>
                    
        """);
        } else {
            System.err.println("ACS Server: Callback FAILED for TokenA: " + tokenA);

            // Reverting local status
            pendingTransactions.put(tokenA, "PENDING");

            // Show error to user, even if payment was 'confirmed', as the merchant wasn't notified.
            ctx.status(500).html("""
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Bank Application</title>
<style>
  body {
    font-family: Arial, sans-serif;
    margin: 0;
    padding: 0;
    background-color: #F5F5F5;
    color: #0A2463;
    display: flex;
    justify-content: center;
    align-items: center;
    height: 100vh;
  }
  h1 {
    color: #0A2463;
    text-align: center;
    margin-bottom: 20px;
  }
  p {
    text-align: center;
    margin-bottom: 20px;
  }
  a {
    color: #00BFA5;
    text-decoration: none;
    font-weight: bold;
  }
  a:hover {
    text-decoration: underline;
  }
  form {
    background-color: #FFFFFF;
    padding: 20px;
    border-radius: 8px;
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
    width: 300px;
    text-align: center;
  }
  label {
    display: block;
    margin-bottom: 10px;
    text-align: left;
  }
  input[type="text"],
  input[type="password"] {
    width: 100%%;
    padding: 10px;
    margin-top: 5px;
    margin-bottom: 15px;
    border: 1px solid #0A2463;
    border-radius: 4px;
    box-sizing: border-box;
  }
  button {
    background-color: #00BFA5;

    color: white;
    border: none;
    padding: 10px 20px;
    border-radius: 4px;
    cursor: pointer;
    font-size: 16px;
    transition: background-color 0.3s ease;
  }
  button:hover {
    background-color: #009688;

  }
  .error {
    color: #FF5252;

    text-align: center;
  }
</style>
</head>
<body>

  <!-- Processing Error Page -->
  <div id="processing-error">
    <h1 class="error">Processing Error</h1>
    <p>Could not complete the final step. Please contact support.</p>
  </div>



  </div>
</body>
</html>
        """);

        }
    };

    // Handler for Step 9 (alternative path): Display failure page for initially invalid cards
    private static Handler handleFailedPage = ctx -> {
        String reason = ctx.queryParam("reason");
        if (reason == null) {
            System.err.println("ACS Server: recieved empty reason parameter");
            reason = "generic_error";
        }
        String tokenA = ctx.queryParam("tokenA");
        if (tokenA == null) {
            System.err.println("ACS Server: recieved empty tokenA parameter");
            tokenA = "N/A";
        }
        System.out.println("ACS Server: Displaying failure page. Reason: " + reason + ", Token: " + tokenA);
        System.out.println("ACS Server: Displaying failure page. Reason: " + reason + ", Token: " + tokenA);
        ctx.status(400).html("<h1>Payment Failed</h1><p>Reason: " + reason + "</p><p>Reference: " + tokenA + "</p>");
        // Note: No callback needed here as the failure was determined before user interaction.
    };


    // Handler for Step 9: Bank Login Page
    private static Handler handleLoginGet = ctx -> {
        String tokenA = ctx.queryParam("tokenA");
        if (tokenA == null || !pendingTransactions.containsKey(tokenA)) {
            ctx.status(400).html("<h1>Invalid or Expired Payment Token</h1>");
            return;
        }

        ctx.html("""
<!DOCTYPE html>
<html lang="en">

<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Bank Application</title>
<style>
  body {
    font-family: Arial, sans-serif;
    margin: 0;
    padding: 0;
    background-color: #F5F5F5;

    color: #0A2463;

    display: flex;
    justify-content: center;
    align-items: center;
    height: 100vh;
  }

  h1 {
    color: #0A2463;

    text-align: center;
    margin-bottom: 20px;
  }

  p {
    text-align: center;
    margin-bottom: 20px;
  }

  a {
    color: #00BFA5;

    text-decoration: none;
    font-weight: bold;
  }

  a:hover {
    text-decoration: underline;
  }

  form {
    background-color: #FFFFFF;

    padding: 20px;
    border-radius: 8px;
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
    width: 300px;
    text-align: center;
  }

  label {
    display: block;
    margin-bottom: 10px;
    text-align: left;
  }

  input[type="text"],
  input[type="password"] {
    width: 100%%;
    padding: 10px;
    margin-top: 5px;
    margin-bottom: 15px;
    border: 1px solid #0A2463;

    border-radius: 4px;
    box-sizing: border-box;
  }

  button {
    background-color: #00BFA5;

    color: white;
    border: none;
    padding: 10px 20px;
    border-radius: 4px;
    cursor: pointer;
    font-size: 16px;
    transition: background-color 0.3s ease;
  }

  button:hover {
    background-color: #009688;

  }

  .error {
    color: #FF5252;

    text-align: center;
  }
</style>
</head>

<body>


  <!-- Bank Login Page -->
  <div id="bank-login">
    <h1>Bank Login</h1>
    <form action="/bank-login" method="post">
      <input type="hidden" name="tokenA" value="%s">
      <label>Name: <input type="text" name="clientName" required></label><br>
      <label>Password: <input type="password" name="clientPassword" required></label><br>
      <label>Card Number: <input type="text" name="clientCard" required></label><br>
      <button type="submit">Login</button>
    </form>
  </div>





</body>

</html>

    """.formatted(tokenA));
    };

    // Handler for Step 9: Bank Login Submit
    private static Handler handleLoginSubmit =ctx -> {
        String tokenA = ctx.formParam("tokenA");
        String name = ctx.formParam("clientName");
        String password = ctx.formParam("clientPassword");
        String card = ctx.formParam("clientCard");

        if (tokenA == null || name == null || password == null || card == null) {
            ctx.status(400).result("Missing login information");
            return;
        }

        LoginSession session = loginStatus.computeIfAbsent(tokenA, k -> new LoginSession());

        if (session.attempts >= 3) {
            // Cancel the transaction after too many failed attempts
            pendingTransactions.put(tokenA, "CANCELLED");
            loginStatus.remove(tokenA); // Cleanup
            ctx.redirect("/payment-failed?reason=too_many_attempts&tokenA=" + URLEncoder.encode(tokenA, StandardCharsets.UTF_8));
            return;
        }

        String clientPassword = "securePass"; // TODO - check with db data

        if (VALID_NAME.equals(name) && card.equals(VALID_CARD_NUMBER) && password.equals(clientPassword)) {
            session.isLoggedIn = true;
            session.attempts = 0; // Reset on success
            ctx.redirect("/validate-payment?tokenA=" + URLEncoder.encode(tokenA, StandardCharsets.UTF_8));
        } else {
            session.attempts++;
            int remaining = 3 - session.attempts;
            ctx.status(401).html("""
            <!DOCTYPE html>
                        <html lang="en">
                    
                        <head>
                          <meta charset="UTF-8">
                          <meta name="viewport" content="width=device-width, initial-scale=1.0">
                          <title>Bank Application</title>
                        <style>
                          body {
                            font-family: Arial, sans-serif;
                            margin: 0;
                            padding: 0;
                            background-color: #F5F5F5;
                    
                            color: #0A2463;
                    
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            height: 100vh;
                          }
                    
                          h1 {
                            color: #0A2463;
                    
                            text-align: center;
                            margin-bottom: 20px;
                          }
                    
                          p {
                            text-align: center;
                            margin-bottom: 20px;
                          }
                    
                          a {
                            color: #00BFA5;
                    
                            text-decoration: none;
                            font-weight: bold;
                          }
                    
                          a:hover {
                            text-decoration: underline;
                          }
                    
                          form {
                            background-color: #FFFFFF;
                    
                            padding: 20px;
                            border-radius: 8px;
                            box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                            width: 300px;
                            text-align: center;
                          }
                    
                          label {
                            display: block;
                            margin-bottom: 10px;
                            text-align: left;
                          }
                    
                          input[type="text"],
                          input[type="password"] {
                            width: 100%%;
                            padding: 10px;
                            margin-top: 5px;
                            margin-bottom: 15px;
                            border: 1px solid #0A2463;
                    
                            border-radius: 4px;
                            box-sizing: border-box;
                          }
                    
                          button {
                            background-color: #00BFA5;
                    
                            color: white;
                            border: none;
                            padding: 10px 20px;
                            border-radius: 4px;
                            cursor: pointer;
                            font-size: 16px;
                            transition: background-color 0.3s ease;
                          }
                    
                          button:hover {
                            background-color: #009688;
                          }
                          .error {
                            color: #FF5252;
                            text-align: center;
                          }
                        </style>
                        </head>
                        <body>
                          <!-- Login Failed Page -->
                          <div id="login-failed">
                            <h1>Login Failed</h1>
                            <p>Invalid credentials. You have %d attempt(s) remaining.</p>
                            <a style="display: flex; justify-content: center;" href="/bank-login?tokenA=%s">Try Again</a>
                          </div>
                        </body>
                        </html>
        """.formatted(Math.max(0, remaining), URLEncoder.encode(tokenA, StandardCharsets.UTF_8)));
        }
    };

    // Step 10: Method to send callback to the Merchant Backend
    private static boolean sendCallbackToMerchant(String tokenA, boolean isSuccessful) {
        try {
            System.out.println("ACS Server - sendCallbackToMerchant - tokenA: " + tokenA + ", isSuccessful: " + isSuccessful);
            JSONObject payload = new JSONObject();
            payload.put("orderId", tokenA);
            payload.put("status", isSuccessful ? "paid" : "failed");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MERCHANT_CALLBACK_URL))
                    .header("Content-Type", "application/json")
                    .header("accept", "application/json")
                    .header("Authorization", "Bearer " + Config.JWT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            System.out.println("ACS Server - sendCallbackToMerchant - request: " + request);

            // Send request asynchronously (can use .send() for synchronous)
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("ACS Server - sendCallbackToMerchant - response: " + response);
            System.out.println("ACS Server: Callback response status code: " + response.statusCode());
            System.out.println("ACS Server: Callback response body: " + response.body());

            // Check if the merchant backend acknowledged successfully (e.g., 2xx status code)
            return response.statusCode() >= 200 && response.statusCode() < 300;

        } catch (Exception e) {
            System.err.println("ACS Server: Failed to send callback for token " + tokenA + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
// End of AcsServer.java //