import java.util.Scanner;
import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        File currentDir = new File(System.getProperty("user.dir"));

        while (true) {
            System.out.print("$ ");
            String input = sc.nextLine();

            if (input.equals("exit")) {
                break;

            } else if (input.equals("pwd")) {
                System.out.println(currentDir.getAbsolutePath());

            } else if (input.startsWith("cd ")) {

                String path = input.substring(3);
                File dir;

                if (new File(path).isAbsolute()) {
                    dir = new File(path);
                } else {
                    dir = new File(currentDir, path);
                }

                if (dir.exists() && dir.isDirectory()) {
                    currentDir = dir.getCanonicalFile();
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }

            } else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));

            } else if (input.startsWith("type ")) {

                String command = input.substring(5);

                if (command.equals("echo") || command.equals("exit") || command.equals("type") || command.equals("pwd") || command.equals("cd")) {
                    System.out.println(command + " is a shell builtin");
                } else {

                    String pathEnv = System.getenv("PATH");
                    String[] paths = pathEnv.split(File.pathSeparator);

                    boolean found = false;

                    for (String dir : paths) {
                        File file = new File(dir, command);

                        if (file.exists() && file.canExecute()) {
                            System.out.println(command + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(command + ": not found");
                    }
                }

            } else {

                String[] parts = input.split(" ");
                String command = parts[0];

                String pathEnv = System.getenv("PATH");
                String[] paths = pathEnv.split(File.pathSeparator);

                File executable = null;

                for (String dir : paths) {
                    File file = new File(dir, command);
                    if (file.exists() && file.canExecute()) {
                        executable = file;
                        break;
                    }
                }

                if (executable != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.directory(currentDir);
                    pb.inheritIO();
                    Process p = pb.start();
                    p.waitFor();
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }
}