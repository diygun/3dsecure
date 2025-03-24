import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public class UserCreator {
    public static void main(String[] args) throws NoSuchAlgorithmException, IOException {
        String username = "simon";
        String password = "aaabbbcccsecurise";

        // Générer un sel et hacher le mot de passe
        String salt = PasswordUtils.generateSalt();
        String hashedPassword = PasswordUtils.hashPassword(password, salt);

        // Ajouter l'utilisateur dans le fichier
        BufferedWriter writer = new BufferedWriter(new FileWriter("./usersDb/users.txt", true));
        writer.write(username + ";" + hashedPassword + ";" + salt);
        writer.newLine();
        writer.close();

        System.out.println("Utilisateur créé et ajouté");
    }
}
