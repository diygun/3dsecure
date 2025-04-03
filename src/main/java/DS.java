import io.javalin.Javalin;
import io.javalin.http.Handler;
import modules.PaymentInfo;

public class DS {

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7070);

        app.post("/payment", handlePayment);
    }

    private static Handler handlePayment = ctx -> {
        PaymentInfo paymentInfo = ctx.bodyAsClass(PaymentInfo.class);

        if (paymentInfo.getClientName() == null || paymentInfo.getClientName().isEmpty() ||
                paymentInfo.getCardNumber() == null || paymentInfo.getCardNumber().isEmpty() ||
                paymentInfo.getMonth() <= 0 || paymentInfo.getYear() <= 0 ||
                paymentInfo.getCvv() <= 0 ||
                paymentInfo.getDueAmount() <= 0 || paymentInfo.getReceiverIdToken() == null || paymentInfo.getReceiverIdToken().isEmpty()) {

            ctx.status(400).result("All fields are required");
            return;
        }


        System.out.println("Payment information received successfully : " + paymentInfo.getClientName());
        System.out.println("Card Number: " + paymentInfo.getCardNumber());
        System.out.println("Month: " + paymentInfo.getMonth() + "/" + paymentInfo.getYear());
        System.out.println("CVV: " + paymentInfo.getCvv());
        System.out.println("Due Amount: " + paymentInfo.getDueAmount());
        System.out.println("Receiver ID Token: " + paymentInfo.getReceiverIdToken());

        if( paymentInfo.getCardNumber() == "1234123412341234" && paymentInfo.getMonth() > 0 && paymentInfo.getMonth() <= 12) {
            // request info is client in the database
            // VERQ to the ACS



            ctx.status(200).result("Payment information is valid");
        } else {
            System.out.println("Invalid card number or month");
            ctx.status(400).result("Invalid card number or month");
            return;
        }
    };
}