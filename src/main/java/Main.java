import java.util.*;
import java.io.File;

public class Main {

    public static List<String> parseInput(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\'') {
                inQuotes = !inQuotes;
            }
            else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            }
            else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    public static void main(String[] args) throws Exception {

        Scanner sc = new Scanner(System.in);
        File currentDir = new File(System.getProperty("user.dir"));

        while (true) {

            System.out.print("$ ");
            String input = sc.nextLine();

            List<String> parts = parseInput(input);

            if (parts.size() == 0) continue;

            String command = parts.get(0);

            if (command.equals("exit")) {
                break;

            } else if (command.equals("pwd")) {
                System.out.println(currentDir.getAbsolutePath());

            } else if (command.equals("cd")) {

                if (parts.size() < 2) continue;

                String path = parts.get(1);
                File dir;

                if (path.equals("~")) {
                    dir = new File(System.getenv("HOME"));
                } else if (new File(path).isAbsolute()) {
                    dir = new File(path);
                } else {
                    dir = new File(currentDir, path);
                }

                if (dir.exists() && dir.isDirectory()) {
                    currentDir = dir.getCanonicalFile();
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }

            } else if (command.equals("echo")) {

                for (int i = 1; i < parts.size(); i++) {
                    if (i > 1) System.out.print(" ");
                    System.out.print(parts.get(i));
                }
                System.out.println();

            } else if (command.equals("type")) {

                if (parts.size() < 2) continue;

                String cmd = parts.get(1);

                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd") || cmd.equals("cd")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {

                    String pathEnv = System.getenv("PATH");
                    String[] paths = pathEnv.split(File.pathSeparator);

                    boolean found = false;

                    for (String dir : paths) {
                        File file = new File(dir, cmd);

                        if (file.exists() && file.canExecute()) {
                            System.out.println(cmd + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(cmd + ": not found");
                    }
                }

            } else {

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