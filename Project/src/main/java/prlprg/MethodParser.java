package prlprg;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

record MethodInstance(String nm, String src, List<MVar> args, List<MVar> locals, String retType, List<Operation> body) {

    @Override
    public String toString() {
        return "MethodInstance for " + nm + "\n"
                + src + "\n"
                + "Arguments\n"
                + args.stream().map(MVar::toString).reduce("", (a, b) -> a + b + "\n")
                + "Locals\n"
                + locals.stream().map(MVar::toString).reduce("", (a, b) -> a + b + "\n")
                + "Body:: " + retType + "\n"
                + body.stream().map(Operation::toString).reduce("", (a, b) -> a + b + "\n");
    }
}

record MVar(String nm, String ty) {

    @Override
    public String toString() {
        return nm + " :: " + ty;
    }
}

record Operation(String op) {

    @Override
    public String toString() {
        return op;
    }
}

class MethodParser {

    MethodInstance parseMethod(String input) {
        var lines = new ArrayList<String>(Arrays.asList(input.split("\n")));
        boolean parsingArguments = false;
        boolean parsingLocals = false;
        String methodName = null;
        String sourceInfo = null;
        String bodyType = null;
        var arguments = new ArrayList<MVar>();
        var locals = new ArrayList<MVar>();
        while (!lines.isEmpty()) {
            var line = lines.removeFirst().trim();
            if (line.startsWith("MethodInstance for")) {
                String[] parts = line.split(" ");
                methodName = parts[2];
                sourceInfo = lines.removeFirst().trim();
            } else if (line.equals("Arguments")) {
                parsingArguments = true;
            } else if (line.equals("Locals")) {
                parsingArguments = false;
                parsingLocals = true;
            } else if (line.startsWith("Body::")) {
                bodyType = line.substring(6).trim();
                break;
            } else if (parsingArguments) {
                try {
                arguments.add(parseVar(line));
                } catch (Throwable e) {
                   // Failed to parse, ignore
                }
            } else if (parsingLocals) {
                try {
                locals.add(parseVar(line));
                } catch (Throwable e) {
                   // Failed to parse, ignore
                }
            } else {
                 // throw new Error("Cannot parse line: " + line);
            }
        }
        if (methodName == null) {
            return new MethodInstance("unknown", "unknown", arguments, locals, "null", new ArrayList<Operation>());
        }
        return new MethodInstance(methodName, sourceInfo, arguments, locals, bodyType, parseBody(lines));
    }

    private MVar parseVar(String line) {
        String[] parts = line.split("::");
        return new MVar(parts[0].trim(), parts[1].trim());
    }

    List<Operation> parseBody(ArrayList<String> lines) {
        List<Operation> operations = new ArrayList<>();
        for (String line : lines) {
            var ps = line.split("\\s+");
            if (ps.length < 2) {
                //throw new Error("Cannot parse line: " + line);
                continue; // Ignore weird stuff -- best effort parsing
            }
            var pos = 0;
            var start = ps[pos++].trim();
            if (start.isEmpty()) {
                continue;
            }
            var c = start.charAt(0);
            if (c == '│') {
                // ok
            } else if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '└') {
                // ok
            } else {
                continue;
                //throw new Error("Cannot parse line: " + line + " =" + start + "=" + c + "=");
            }
            var str = "";
            for (int i = pos; i < ps.length; i++) {
                str += ps[i] + " ";
            }
            if (!str.startsWith("goto") && !str.startsWith("return")) {
                operations.add(new Operation(str.trim()));
            }
        }
        return operations;
    }

    public static void main(String[] args) {
        File dir = new File("/tmp");  // Replace with your directory path

        // FilenameFilter to filter files ending with .txt
        FilenameFilter filter = (file, name) -> name.endsWith(".txt");

        // List all files in the directory with the specified filter
        File[] files = dir.listFiles(filter);

        if (files != null) {
            for (File file : files) {
                // Call parseFile for each .txt file
                try {
                    var m = parseFile(file.toString());
                    System.out.println(m);
                } catch (Throwable e) {
                    System.out.println("Error parsing file " + file.toString() + ": " + e.getMessage());
                }
            }
        } else {
            System.out.println("The directory is empty or does not exist.");
        }

    }

    static MethodInstance parseFile(String filename) {
        var p = new MethodParser();
        String contents;
        try {
            contents = Files.readString(Paths.get(filename));
        } catch (IOException e) {
            return null;
        }
        return p.parseMethod(contents);
    }
}
