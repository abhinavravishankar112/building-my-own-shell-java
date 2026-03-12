import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class Main {
    public static List<String> parseInput(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && !inSingle) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            }
            else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            }
            else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
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

    private static final List<String> BUILTINS = Arrays.asList("echo", "exit", "type", "pwd", "cd", "history");

    private static final int HISTORY_MAX = 2000;
    private static final String HISTORY_FILE = System.getProperty("user.home") + File.separator + ".my_shell_history";

    private static final List<String> history = new ArrayList<>();
    private static int historyNavIndex = -1; // -1 means not navigating
    private static String historyNavScratch = ""; // buffer before starting to navigate

    private static String savedSttyConfig = null;

    private static void execStty(String args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty").inheritIO().start();
        int code = p.waitFor();
        if (code != 0) throw new IOException("stty failed: " + args);
    }

    private static String getSttyConfig() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("sh", "-c", "stty -g < /dev/tty").start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(baos);
        }
        int code = p.waitFor();
        if (code != 0) throw new IOException("stty -g failed");
        return baos.toString(StandardCharsets.UTF_8).trim();
    }

    private static void enableRawMode() throws IOException, InterruptedException {
        savedSttyConfig = getSttyConfig();
        // -icanon: character-at-a-time, -echo: don't echo input, min 1 char
        execStty("-icanon -echo min 1 time 0");
    }

    private static void disableRawMode() {
        if (savedSttyConfig == null) return;
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty " + savedSttyConfig + " < /dev/tty").inheritIO().start();
            p.waitFor();
        } catch (Exception ignored) {}
    }

    private static final class ReadResult {
        final String line;
        final boolean eof;
        ReadResult(String line, boolean eof) { this.line = line; this.eof = eof; }
    }

    private static void printPrompt() {
        System.out.print("$ ");
        System.out.flush();
    }

    private static void redrawLine(String prompt, String buffer) {
        // Clear line: CR + ANSI clear to end
        System.out.print("\r");
        System.out.print("\u001B[0K");
        System.out.print(prompt);
        System.out.print(buffer);
        System.out.flush();
    }

    private static void printlnAndRePrompt(String prompt, String buffer, String lineToPrint) {
        System.out.print("\r");
        System.out.print("\u001B[0K");
        System.out.println(lineToPrint);
        System.out.print(prompt);
        System.out.print(buffer);
        System.out.flush();
    }

    private static ReadResult readLineWithEditing(InputStream in, File currentDir) throws IOException {
        final String prompt = "$ ";
        StringBuilder buf = new StringBuilder();

        historyNavIndex = -1;
        historyNavScratch = "";

        while (true) {
            int b = in.read();
            if (b == -1) return new ReadResult("", true);

            char c = (char) b;

            // Enter
            if (c == '\n' || c == '\r') {
                System.out.print("\r\n");
                System.out.flush();
                return new ReadResult(buf.toString(), false);
            }

            // Ctrl-D (EOF) when buffer empty
            if (c == 4) { // EOT
                if (buf.length() == 0) return new ReadResult("", true);
                // otherwise ignore
                continue;
            }

            // Backspace (DEL or BS)
            if (c == 127 || c == 8) {
                if (buf.length() > 0) {
                    buf.setLength(buf.length() - 1);
                    redrawLine(prompt, buf.toString());
                }
                continue;
            }

            // Tab completion
            if (c == '\t') {
                String updated = handleTabCompletion(buf.toString(), currentDir);
                // handleTabCompletion prints listing if needed; it returns new buffer
                buf = new StringBuilder(updated);
                redrawLine(prompt, buf.toString());
                continue;
            }

            // Escape sequences (arrows etc.)
            if (c == 27) { // ESC
                in.mark(3);
                int b1 = in.read();
                int b2 = in.read();
                if (b1 == -1 || b2 == -1) continue;

                if (b1 == '[') {
                    if (b2 == 'A') { // Up
                        String newBuf = historyUp(buf.toString());
                        buf = new StringBuilder(newBuf);
                        redrawLine(prompt, buf.toString());
                        continue;
                    } else if (b2 == 'B') { // Down
                        String newBuf = historyDown();
                        buf = new StringBuilder(newBuf);
                        redrawLine(prompt, buf.toString());
                        continue;
                    } else if (b2 == 'C') { // Right (not implemented)
                        continue;
                    } else if (b2 == 'D') { // Left (not implemented)
                        continue;
                    }
                }

                // Unknown escape, ignore
                continue;
            }

            // Printable characters
            if (!Character.isISOControl(c)) {
                // If user types while navigating history, stop navigation
                if (historyNavIndex != -1) {
                    historyNavIndex = -1;
                    historyNavScratch = "";
                }
                buf.append(c);
                System.out.print(c);
                System.out.flush();
            }
        }
    }

    private static String historyUp(String currentBuffer) {
        if (history.isEmpty()) return currentBuffer;

        if (historyNavIndex == -1) {
            historyNavScratch = currentBuffer;
            historyNavIndex = history.size(); // one past last
        }
        if (historyNavIndex > 0) {
            historyNavIndex--;
            return history.get(historyNavIndex);
        }
        return history.get(0);
    }

    private static String historyDown() {
        if (history.isEmpty() || historyNavIndex == -1) return "";

        if (historyNavIndex < history.size()) historyNavIndex++;

        if (historyNavIndex >= history.size()) {
            historyNavIndex = -1;
            return historyNavScratch;
        }
        return history.get(historyNavIndex);
    }

    private static String handleTabCompletion(String buffer, File currentDir) {
        // Determine which token is being completed:
        // - if first token -> command completion (builtins + PATH executables)
        // - else -> file completion
        CompletionContext ctx = computeCompletionContext(buffer);

        List<String> candidates;
        if (ctx.tokenIndex == 0) {
            candidates = completeCommand(ctx.partial);
        } else {
            candidates = completePath(ctx.partial, currentDir);
        }

        if (candidates.isEmpty()) {
            // no-op (missing completion)
            return buffer;
        }

        // If exactly 1 candidate, insert remainder (and a space if completing command or file without trailing slash)
        if (candidates.size() == 1) {
            String chosen = candidates.get(0);
            return applyCompletion(buffer, ctx, chosen, true);
        }

        // Multiple candidates: complete to LCP if possible; otherwise print list
        String lcp = longestCommonPrefix(candidates);
        if (lcp != null && lcp.length() > ctx.partial.length()) {
            return applyCompletion(buffer, ctx, lcp, false);
        }

        // Print options
        StringBuilder sb = new StringBuilder();
        for (String s : candidates) {
            sb.append(s).append("  ");
        }
        System.out.print("\r");
        System.out.print("\u001B[0K");
        System.out.println(sb.toString().trim());
        System.out.print("$ ");
        System.out.print(buffer);
        System.out.flush();
        return buffer;
    }

    private static final class CompletionContext {
        final int tokenIndex;
        final int tokenStart; // start index in buffer
        final int tokenEnd;   // end index in buffer (exclusive)
        final String partial;
        CompletionContext(int tokenIndex, int tokenStart, int tokenEnd, String partial) {
            this.tokenIndex = tokenIndex;
            this.tokenStart = tokenStart;
            this.tokenEnd = tokenEnd;
            this.partial = partial;
        }
    }

    private static CompletionContext computeCompletionContext(String buffer) {
        // Simple tokenization for completion:
        // - split by whitespace, but keep last token boundaries
        // - ignore quoting complexity for completion (good enough for a small shell)
        int n = buffer.length();
        int i = 0;
        int tokenIndex = 0;
        int lastTokenStart = 0;
        int lastTokenEnd = 0;
        boolean inToken = false;

        while (i < n) {
            char c = buffer.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    inToken = false;
                    tokenIndex++;
                }
                i++;
                continue;
            } else {
                if (!inToken) {
                    inToken = true;
                    lastTokenStart = i;
                }
                lastTokenEnd = i + 1;
                i++;
            }
        }

        // If buffer ends with whitespace, we're completing a new empty token
        if (n > 0 && Character.isWhitespace(buffer.charAt(n - 1))) {
            return new CompletionContext(tokenIndex, n, n, "");
        }

        // Otherwise completing current last token
        String partial = buffer.substring(lastTokenStart, lastTokenEnd);
        int currentTokenIndex = inToken ? tokenIndex : tokenIndex; // tokenIndex points to next token if ended; but inToken means current
        return new CompletionContext(currentTokenIndex, lastTokenStart, lastTokenEnd, partial);
    }

    private static String applyCompletion(String buffer, CompletionContext ctx, String completion, boolean maybeAddSpace) {
        StringBuilder out = new StringBuilder();
        out.append(buffer, 0, ctx.tokenStart);
        out.append(completion);
        out.append(buffer.substring(ctx.tokenEnd));

        // Add space if we completed an entire token and cursor is at end
        boolean cursorAtEnd = ctx.tokenEnd == buffer.length();
        if (maybeAddSpace && cursorAtEnd) {
            // If completion ends with '/' keep going (directory)
            if (!completion.endsWith("/")) out.append(' ');
        }
        return out.toString();
    }

    private static List<String> completeCommand(String partial) {
        Set<String> res = new TreeSet<>();

        // Builtins
        for (String b : BUILTINS) {
            if (b.startsWith(partial)) res.add(b);
        }

        // PATH executables
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] paths = pathEnv.split(File.pathSeparator);
            for (String dir : paths) {
                File d = new File(dir);
                File[] files = d.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith(partial) && f.isFile() && f.canExecute()) {
                        res.add(name);
                    }
                }
            }
        }

        return new ArrayList<>(res);
    }

    private static List<String> completePath(String partial, File currentDir) {
        // Expand ~ in the partial for lookup, but keep the returned candidate using the same prefix style.
        String expanded = expandTilde(partial);
        File base;
        String prefixInDir;

        int lastSlash = expanded.lastIndexOf('/');
        if (lastSlash >= 0) {
            String dirPart = expanded.substring(0, lastSlash + 1);
            prefixInDir = expanded.substring(lastSlash + 1);
            base = resolvePathForCompletion(dirPart, currentDir);
        } else {
            base = currentDir;
            prefixInDir = expanded;
        }

        File[] files = base.listFiles();
        if (files == null) return Collections.emptyList();

        List<String> matches = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            if (!name.startsWith(prefixInDir)) continue;

            String candidateName = name + (f.isDirectory() ? "/" : "");

            // Return candidate relative to what user typed (keep dirPart)
            if (lastSlash >= 0) {
                String dirPartOriginal = partial.substring(0, partial.lastIndexOf('/') + 1);
                matches.add(dirPartOriginal + candidateName);
            } else {
                matches.add(candidateName);
            }
        }

        Collections.sort(matches);
        return matches;
    }

    private static File resolvePathForCompletion(String dirPart, File currentDir) {
        // dirPart is expanded (tilde already expanded)
        File dir = new File(dirPart);
        if (dir.isAbsolute()) return dir;
        return new File(currentDir, dirPart);
    }

    private static String expandTilde(String s) {
        if (s.equals("~")) return System.getProperty("user.home");
        if (s.startsWith("~/")) return System.getProperty("user.home") + s.substring(1);
        return s;
    }

    private static String longestCommonPrefix(List<String> strings) {
        if (strings == null || strings.isEmpty()) return null;
        String first = strings.get(0);
        int max = first.length();
        for (int i = 0; i < max; i++) {
            char ch = first.charAt(i);
            for (int j = 1; j < strings.size(); j++) {
                String s = strings.get(j);
                if (i >= s.length() || s.charAt(i) != ch) {
                    return first.substring(0, i);
                }
            }
        }
        return first;
    }

    private static void loadHistory() {
        File f = new File(HISTORY_FILE);
        if (!f.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) history.add(line);
            }
            trimHistory();
        } catch (IOException ignored) {}
    }

    private static void appendHistoryToFile(String line) {
        if (line == null || line.isBlank()) return;
        try (FileOutputStream fos = new FileOutputStream(HISTORY_FILE, true);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter bw = new BufferedWriter(osw)) {
            bw.write(line);
            bw.newLine();
        } catch (IOException ignored) {}
    }

    private static void trimHistory() {
        if (history.size() <= HISTORY_MAX) return;
        int toRemove = history.size() - HISTORY_MAX;
        history.subList(0, toRemove).clear();
    }

    public static void main(String[] args) throws Exception {

        File currentDir = new File(System.getProperty("user.dir"));
        loadHistory();

        // Ensure terminal restored even if we crash
        Runtime.getRuntime().addShutdownHook(new Thread(Main::disableRawMode));

        enableRawMode();
        try {
            InputStream in = System.in;

            while (true) {
                printPrompt();

                ReadResult rr = readLineWithEditing(in, currentDir);
                if (rr.eof) break;

                String input = rr.line;
                if (input == null) continue;

                // Store history (avoid duplicates in a row)
                if (!input.isBlank()) {
                    if (history.isEmpty() || !history.get(history.size() - 1).equals(input)) {
                        history.add(input);
                        trimHistory();
                        appendHistoryToFile(input); // append-on-enter (simple + robust)
                    }
                }

                List<String> parts = parseInput(input);
                if (parts.size() == 0) continue;

                String command = parts.get(0);

                if (command.equals("exit")) {
                    break;

                } else if (command.equals("pwd")) {
                    System.out.println(currentDir.getAbsolutePath());

                } else if (command.equals("history")) {
                    // Listing history
                    for (int i = 0; i < history.size(); i++) {
                        System.out.printf("%5d  %s%n", i + 1, history.get(i));
                    }

                } else if (command.equals("cd")) {

                    if (parts.size() < 2) continue;

                    String path = parts.get(1);
                    File dir;

                    if (path.equals("~")) {
                        dir = new File(System.getenv("HOME"));
                    } else if (path.startsWith("~/")) {
                        dir = new File(System.getenv("HOME"), path.substring(2));
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

                    if (BUILTINS.contains(cmd)) {
                        System.out.println(cmd + " is a shell builtin");
                    } else {

                        String pathEnv = System.getenv("PATH");
                        String[] paths = (pathEnv == null) ? new String[0] : pathEnv.split(File.pathSeparator);

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
                    String[] paths = (pathEnv == null) ? new String[0] : pathEnv.split(File.pathSeparator);

                    File executable = null;

                    for (String dir : paths) {
                        File file = new File(dir, command);
                        if (file.exists() && file.canExecute()) {
                            executable = file;
                            break;
                        }
                    }

                    if (executable != null) {

                        // Temporarily disable raw mode so subprocesses behave normally
                        disableRawMode();
                        try {
                            ProcessBuilder pb = new ProcessBuilder(parts);
                            pb.directory(currentDir);
                            pb.inheritIO();

                            Process p = pb.start();
                            p.waitFor();
                        } finally {
                            enableRawMode();
                        }

                    } else {
                        System.out.println(command + ": command not found");
                    }
                }
            }
        } finally {
            disableRawMode();
        }
    }
}