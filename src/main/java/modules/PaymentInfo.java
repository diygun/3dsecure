package modules;

import java.math.BigInteger;

// PaymentInfo.java
public class PaymentInfo {
    private String clientName;
    private String cardNumber;
    private int month;
    private int year;
    private int cvv;
    private float dueAmount;
    private String receiverIdToken;

    // Getters and setters
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }

    public int getMonth() { return month; }
    public void setMonth(int month) { this.month = month; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getCvv() { return cvv; }
    public void setCvv(int cvv) { this.cvv = cvv; }

    public float getDueAmount() { return dueAmount; }
    public void setDueAmount(float dueAmount) { this.dueAmount = dueAmount; }
    public String getReceiverIdToken() { return receiverIdToken; }
    public void setReceiverIdToken(String receiverIdToken) { this.receiverIdToken = receiverIdToken; }
}