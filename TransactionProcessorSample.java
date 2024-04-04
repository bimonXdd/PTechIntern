package com.playtech.assignment;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


// This template shows input parameters format.
// It is otherwise not mandatory to use, you can write everything from scratch if you wish.
public class TransactionProcessorSample {

    public static void main(final String[] args) throws IOException {
        List<User> users = TransactionProcessorSample.readUsers(Paths.get(args[0]));

        Transaction testTrans = new Transaction("10403", 4, "DEPOSIT", 80.00, "CARD", "544918450459874888");
        List<Transaction> transactions = TransactionProcessorSample.readTransactions(Paths.get(args[1]));
        List<BinMapping> binMappings = TransactionProcessorSample.readBinMappings(Paths.get(args[2]));
        List<Event> events = TransactionProcessorSample.processTransactions(users, transactions, binMappings);

        for (Event event : events) {
            System.out.println(event.transactionId + ", " + event.status + ", " + event.message);
        }

        TransactionProcessorSample.writeBalances(Paths.get(args[3]), users);
        TransactionProcessorSample.writeEvents(Paths.get(args[4]), events);
    }

    private static List<User> readUsers(final Path filePath) {
        List<User> userList = new ArrayList<>();

        try {
            //add all the users to a list for returning
            for (String userRow : CSVtoStringList(filePath)) {
                String[] user = userRow.split(",");

                //add each user to list
                userList.add(new User(Integer.parseInt(user[0]),     //ID
                        user[1],                         //NAME
                        Double.parseDouble(user[2]),     //BALANCE
                        user[3],                         //COUNTRY
                        user[4] == "1" ? true : false,    //FROZEN
                        Double.parseDouble(user[5]),     //deposit_min
                        Double.parseDouble(user[6]),     // deposit_max
                        Double.parseDouble(user[7]),     //withdraw_min
                        Double.parseDouble(user[8])));   //withdraw_max
            }

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return userList;


    }

    private static List<Transaction> readTransactions(final Path filePath) {
        List<Transaction> transactionList = new ArrayList<>();
        try {
            for (String lineInfo : CSVtoStringList(filePath)) {
                String[] transaction = lineInfo.split(",");
                transactionList.add(new Transaction(transaction[0],   //transaction_id
                        Integer.parseInt(transaction[1]),   //user_id
                        transaction[2],                     //type
                        Double.parseDouble(transaction[3]), //amount
                        transaction[4],                     //method
                        transaction[5]));                    //account_number
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return transactionList;
    }

    private static List<BinMapping> readBinMappings(final Path filePath) {
        List<BinMapping> binMappingList = new ArrayList<>();
        try {
            for (String lineInfo : CSVtoStringList(filePath)) {
                String[] transaction = lineInfo.split(",");
                binMappingList.add(new BinMapping(transaction[0], //name
                        Long.parseLong(transaction[1]),           //range_from
                        Long.parseLong(transaction[2]),           //range_to
                        transaction[3],                           //type
                        transaction[4]));                         //country
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return binMappingList;
    }

    /**
     *
     * @param users
     * @param transactions
     * @param binMappings
     * @return message of transaction
     * @throws IOException
     *
     * @note some of the checks are missing and the output is not currently functional
     */
    private static List<Event> processTransactions(final List<User> users, final List<Transaction> transactions, final List<BinMapping> binMappings) throws IOException {
        List<Event> events = new ArrayList<>();
        Boolean skip = false;


        for (Transaction transaction : transactions) {
            User user = findTransactionUser(transaction, users);
            skip = false;
            for (Event eventCheck : events) {
                if (eventCheck.transactionId != null) {
                    //check if the transaction Id is unique
                    if (!eventCheck.transactionId.equals(transaction.getTransaction_id())) {
                        Event event = new Event();
                        event.transactionId = transaction.getTransaction_id();
                        event.status = Event.STATUS_DECLINED;
                        event.message = "Transaction " + event.transactionId + " already processed (id non-unique)";
                        events.add(event);
                        skip = true;
                        break;
                    }
                }

            }
            if (skip) continue;

            //if user isnt validated
            if (!userValidation(transaction.getUser_id(), users)) {
                Event event = new Event();
                event.status = Event.STATUS_DECLINED;
                if (user != null) {
                    event.message = user.getUsername() + " not found in Users or is frozen";
                } else {
                    event.message = "User not found";
                }
                events.add(event);

            } else if (!validatePaymentMethod(transaction, binMappings)) {
                Event event = new Event();
                event.status = Event.STATUS_DECLINED;


                if (transaction.getMethod().equals("TRANSFER"))
                    event.message = "Invalid iban " + transaction.getAccount_number();
                else if (transaction.getMethod().equals("CARD")) event.message = "Only DC cards allowed; got CC";
                else event.message = "Not valid method in transaction: " + transaction.getMethod();
                events.add(event);

            } else if (!validateUsersCountry(transaction, binMappings, users)) {
                Event event = new Event();
                String userCountry = user.getCountry();
                String bankCountry = ISO3toISO2(findBank(transaction, binMappings).getCountry());
                event.status = Event.STATUS_DECLINED;
                event.message = "Invalid account country " + userCountry + "; expected " + bankCountry;
                events.add(event);
                continue;

            }
            String amountValidationMessage = validateAmount(transaction, users);
            if (!(amountValidationMessage.equals("OK"))) {
                Event event = new Event();
                event.status = Event.STATUS_DECLINED;
                double amount = transaction.getAmount();

                if (transaction.getType().equals("WITHDRAW")) {
                    if (amountValidationMessage.equals("overW"))
                        event.message = "Amount " + amount + " is over the withdraw limit of " + user.getWithdraw_max();
                    if (amountValidationMessage.equals("underW"))
                        event.message = "Amount " + amount + " is under the withdraw limit of " + user.getWithdraw_min();
                }
                if (transaction.getType().equals("DEPOSIT")) {
                    if (amountValidationMessage.equals("overD"))
                        event.message = "Amount " + amount + " is over the deposit limit of " + user.getDeposit_max();
                    if (amountValidationMessage.equals("underD"))
                        event.message = "Amount " + amount + " is under the deposit limit of " + user.getDeposit_min();
                }
                events.add(event);


            } else if (!sufficientBalance(transaction, users)) {
                Event event = new Event();
                event.status = Event.STATUS_DECLINED;
                event.message = "Not enough balance to withdraw " + transaction.getAmount() + " - balance is too low at " + user.getBalance();
                events.add(event);

            } else {
                Event event = new Event();
                event.transactionId = transaction.getTransaction_id();
                event.status = Event.STATUS_APPROVED;
                event.message = "OK";
                events.add(event);
            }
        }

        return events;
    }

    private static void writeBalances(final Path filePath, final List<User> users) {
        // ToDo Implementation
    }

    /**
     * @param userID
     * @param users
     * @return true if account is valid, false if account is frozen
     */
    private static boolean userValidation(int userID, List<User> users) {
        for (User user : users) {
            if (userID == user.getId()) {
                if (user.getFrozen()) return true;
            }
        }
        return false;
    }

    /**
     *
     * @param transaction
     * @param binMappings
     * @return boolean
     *
     * @note validates transaction method
     */
    private static boolean validatePaymentMethod(Transaction transaction, List<BinMapping> binMappings) {
        String type = transaction.getMethod();
        if (type.equals("TRANSFER")) {
            //first four digits go to the end
            StringBuilder IBAN = new StringBuilder(transaction.getAccount_number());
            String firstFour = IBAN.substring(0, 4);
            IBAN.delete(0, 4);
            IBAN.append(firstFour);

            //change the letters to numbers
            for (int i = 0; i < IBAN.length(); i++) {
                char letter = IBAN.charAt(i);

                //if char is letter, convert it to numeric, then delete everything from the beginning of the letter, add the new numeric, then add the rest of the IBAN on top.
                if (Character.isLetter(letter)) {
                    String letterNumber = String.valueOf(Character.getNumericValue(letter));
                    String afterLetter = IBAN.substring(i + 1, IBAN.length());
                    IBAN.delete(i, IBAN.length());
                    IBAN.append(letterNumber).append(afterLetter);
                }
            }
            //if 1 then valid, else not valid
            return Dmod97(IBAN);
        } else if (type.equals("CARD")) {

            if (findBank(transaction, binMappings).getType().equals("DC")) return true;
            return false;
        } else return false;
    }

    /**
     *
     * @param transaction
     * @param binMappings
     * @return Binmapping object of the searched bank
     */
    private static BinMapping findBank(Transaction transaction, List<BinMapping> binMappings) {
        // 1: gets the first 10 letters of the cardNR
        char[] firstTenChars = transaction.getAccount_number().toCharArray();
        StringBuilder firstTenNR = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            firstTenNR.append(firstTenChars[i]);
        }

        Long firstTenNrInt = Long.parseLong(String.valueOf(firstTenNR));
        BinMapping noneFound = new BinMapping("none", 0, 0, "none", "none");
        //check for the range to find if there is a bank that uses CC or DC

        for (BinMapping binMapping : binMappings) {
            if (binMapping.getRange_from() <= firstTenNrInt &&
                    binMapping.getRange_to() >= firstTenNrInt) {
                //because only DC is allowed
                return binMapping;
            }
        }
        return noneFound;


    }

    private static void writeEvents(final Path filePath, final List<Event> events) throws IOException {
        try (final FileWriter writer = new FileWriter(filePath.toFile(), false)) {
            writer.append("transaction_id,status,message\n");
            for (final var event : events) {
                writer.append(event.transactionId).append(",").append(event.status).append(",").append(event.message).append("\n");
            }
        }
    }

    /**
     *
     * @param filePath
     * @return List of info from CSV file
     * @throws IOException
     */
    private static List<String> CSVtoStringList(Path filePath) throws IOException {
        File file = new File(filePath.toString());
        FileReader fr = new FileReader(file);
        BufferedReader br = new BufferedReader(fr);
        String line ;

        List<String> lines = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            lines.add(line);
        }
        lines.removeFirst();
        br.close();
        return lines;
    }

    /**
     *
     * @param IBAN
     * @note Check for IBAN
     */
    private static Boolean Dmod97(StringBuilder IBAN) {
        long N;
        for (int i = 0; i < 3; i++) {

            if (i == 0) {
                N = Long.parseLong(IBAN.substring(0, 9)) % 97;
                IBAN.delete(0, 9);
                IBAN.insert(0, N);
            } else {
                N = Long.parseLong(IBAN.substring(0, 9)) % 97;
                IBAN.delete(0, 9);
                if (i != 3) IBAN.insert(0, N);

            }

        }
        N = Long.parseLong(String.valueOf(IBAN)) % 97;
        return N == 1;
    }

    /**
     *
     * @param ISO3
     * @return ISO2 country code
     * @throws IOException
     */
    private static String ISO3toISO2(String ISO3) throws IOException {
        Path ISOcodes = Path.of("C:\\Users\\Simon\\Downloads\\Playtech Java Assignment 2024 1\\test-data\\manual test data 75% validations\\input\\ISOcodes.csv");
        for (String code : CSVtoStringList(ISOcodes)) {
            String[] line = code.split(",");
            String codeISO3 = line[2];
            String codeISO2 = line[1];

            //remove "" symbols
            String cleanedISO3 = codeISO3.substring(2, codeISO3.length() - 1);
            String cleanedISO2 = codeISO2.substring(2, codeISO2.length() - 1);

            if (cleanedISO3.equals(ISO3)) return cleanedISO2;
        }
        return ISO3;
    }


    /**
     *
     * @param transaction
     * @param binMappings
     * @param users
     * @return boolean if the country info of the transaction is valid
     * @throws IOException
     */
    private static Boolean validateUsersCountry(Transaction transaction, List<BinMapping> binMappings, List<User> users) throws IOException {

        String transactionCountry = findBank(transaction, binMappings).getCountry();
        String userCountry = null;
        userCountry = findTransactionUser(transaction, users).getCountry();

        if (ISO3toISO2(transactionCountry).equals(userCountry)) return true;
        return false;
    }


    /**
     *
     * @param transaction
     * @param users
     * @return user info based on transaction
     */
    private static User findTransactionUser(Transaction transaction, List<User> users) {
        int userID = transaction.getUser_id();
        for (User user : users) {

            if (user.getId() == userID) {
                return user;
            }
        }
        return null;

    }

    /**
     *
     * @param transaction
     * @param users
     * @return boolean if the user has sufficient balance for transaction
     */
    private static Boolean sufficientBalance(Transaction transaction, List<User> users) {
        Double userBalance = findTransactionUser(transaction, users).getBalance();
        if (userBalance < transaction.getAmount()) return false;
        return true;
    }

    /**
     *
     * @param transaction
     * @param users
     * @return boolean if the transactions amount is valid based on the user
     */
    private static String validateAmount(Transaction transaction, List<User> users) {
        double amount = transaction.getAmount();

        String type = transaction.getType();
        User user = findTransactionUser(transaction, users);
        if (user == null) return "user not found";
        if (type.equals("DEPOSIT")) {
            double depoMin = user.getDeposit_min();
            double depoMax = user.getDeposit_max();
            if (amount > depoMax) return "overD";
            else if (amount <= depoMin) return "underD";
            return "OK";
        }
        if (type.equals("WITHDRAW")) {
            double withMin = user.getWithdraw_min();
            double withMax = user.getWithdraw_max();
            if (amount > withMax) return "overW";
            if (amount <= withMin) return "underW";
            return "OK";

        }

        return "Not allowed type";
    }
}


class User {
    private int id;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Boolean getFrozen() {
        return frozen;
    }

    public void setFrozen(Boolean frozen) {
        this.frozen = frozen;
    }

    public double getDeposit_min() {
        return deposit_min;
    }

    public void setDeposit_min(double deposit_min) {
        this.deposit_min = deposit_min;
    }

    public double getDeposit_max() {
        return deposit_max;
    }

    public void setDeposit_max(double deposit_max) {
        this.deposit_max = deposit_max;
    }

    public double getWithdraw_min() {
        return withdraw_min;
    }

    public void setWithdraw_min(double withdraw_min) {
        this.withdraw_min = withdraw_min;
    }

    public double getWithdraw_max() {
        return withdraw_max;
    }

    public void setWithdraw_max(double withdraw_max) {
        this.withdraw_max = withdraw_max;
    }

    private String username;

    private double balance;
    private String country;

    private Boolean frozen;
    private double deposit_min;
    private double deposit_max;
    private double withdraw_min;
    private double withdraw_max;

    public User(int id, String username, double balance, String country, Boolean frozen, double deposit_min, double deposit_max, double withdraw_min, double withdraw_max) {
        this.id = id;
        this.username = username;
        this.balance = balance;
        this.country = country;
        this.frozen = frozen;
        this.deposit_min = deposit_min;
        this.deposit_max = deposit_max;
        this.withdraw_min = withdraw_min;
        this.withdraw_max = withdraw_max;
    }
}

class Transaction {
    private String transaction_id;
    private int user_id;
    private String type;

    private double amount;

    private String method;

    private String account_number;

    public String getTransaction_id() {
        return transaction_id;
    }

    public int getUser_id() {
        return user_id;
    }

    public String getType() {
        return type;
    }

    public double getAmount() {
        return amount;
    }

    public String getMethod() {
        return method;
    }

    public String getAccount_number() {
        return account_number;
    }


    public Transaction(String transaction_id, int user_id, String type, double amount, String method, String account_number) {
        this.transaction_id = transaction_id;
        this.user_id = user_id;
        this.type = resolvType(type);
        this.amount = amount;
        this.method = resolvMethod(method);
        this.account_number = account_number;
    }

    private String resolvType(String type) {
        if (type.equals("DEPOSIT") || type.equals("WITHDRAW")) return type;
        else throw new RuntimeException("Type should be DEPOSIT or WITHDRAW.");
    }

    private String resolvMethod(String method) {
        if (method.equals("CARD") || method.equals("TRANSFER")) return method;
        else throw new RuntimeException("Method should be CARD or TRANSFER.");

    }
}

class BinMapping {
    public String getName() {
        return name;
    }

    public long getRange_from() {
        return range_from;
    }

    public long getRange_to() {
        return range_to;
    }

    public String getType() {
        return type;
    }

    public String getCountry() {
        return country;
    }

    private String name;
    private long range_from;
    private long range_to;
    private String type;
    private String country;

    public BinMapping(String name, long range_from, long range_to, String type, String country) {
        this.name = name;
        this.range_from = range_from;
        this.range_to = range_to;
        this.type = type;
        this.country = country;
    }
}

class Event {
    public static final String STATUS_DECLINED = "DECLINED";
    public static final String STATUS_APPROVED = "APPROVED";

    public String transactionId;
    public String status;
    public String message;
}
