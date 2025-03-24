import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class PasswordUtils {

    public static String generateSalt() {
        // Génère un sel aléatoire de 16 octets
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);  // Encodage en Base64 pour stocker facilement
    }

    public static String hashPassword(String password, String salt) throws NoSuchAlgorithmException {
        // Concatène le mot de passe avec le sel
        String passwordWithSalt = password + salt;

        // Applique l'algorithme SHA-256
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashedBytes = digest.digest(passwordWithSalt.getBytes());

        // Retourne le mot de passe haché en Base64
        return Base64.getEncoder().encodeToString(hashedBytes);
    }

    public static boolean verifyPassword(String password, String storedHashedPassword, String salt) throws NoSuchAlgorithmException {
        // Hache le mot de passe avec le sel et compare avec le mot de passe stocké
        String hashedPassword = hashPassword(password, salt);
        return hashedPassword.equals(storedHashedPassword);
    }
}
