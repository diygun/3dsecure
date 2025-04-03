public class User {
    private final String login;
    private final String hashPassword;
    private final String salt;

    public User(String login, String hashPassword, String salt) {
        this.login = login;
        this.hashPassword = hashPassword;
        this.salt = salt;
    }

    public String getLogin() {
        return login;
    }

    public String getHashPassword() {
        return hashPassword;
    }

    public String getSalt() {
        return salt;
    }
}