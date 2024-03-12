package prlprg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import prlprg.App.Timer;
import prlprg.Parser.MethodInformation;
import java.util.Collections;

/**
 * Class for orchestrating the generation of type stability tests.
 *
 * @author jan
 */
class Orchestrator {

    private static int num = 0; // internal counter for unique names

    /**
     * Generate a unique number in a possibly concurrent context. These numbers are
     * used for temproary director names.
     */
    static synchronized int nextNum() {
        return num++;
    }

    GenDB it; // reference to the GenDB for convenience

    /**
     * Create an orchestrator for the given GenDB.
     */
    Orchestrator() {
        this.it = GenDB.it;
    }

    /**
     * Generate the tests and run them.
     */
    void orchestrate() {
        var ctxt = new Context();

        // Create tests for all *functions* with different arities. Send `Any` to each argument.
        Timer t = new Timer().start();
        createPkgs(ctxt);
        createInitialTests(ctxt, testsForAllFuns());
        App.print("Created " + ctxt.count + " tests in " + t.stop());

        // Get Julia to run all the tests
        t = new Timer().start();
        JuliaUtils.runTests(ctxt.tests, ctxt.tmp);
        App.printSeparator();
        App.print("Executed " + ctxt.count + " tests in " + t.stop());

        // Recover the results from files in the target directory
        t = new Timer().start();
        readResults(ctxt);
        App.print("Read " + ctxt.count + " results in " + t.stop());

        // Summarize results
        App.printSeparator();
        var count = 0;
        var tot = 0;
        var weird = 0;
        var concrete = 0;
        var nothings = 0;
        var concretes = new HashSet<String>();
        var abstracts = new HashSet<String>();

        for (var s : it.sigs.allSigs()) {
            if (s.kwPos() != -1) {
                App.print("Skipping " + s + " because it has a keyword argument");
            }
        }

        for (var inf : it.sigs.allInfos()) {
            tot++;
            if (!inf.results.isEmpty()) {
                count++;
                if (inf.results.size() > 1)
                    weird++;
                else if (inf.results.size() == 1) {
                    var rty = inf.results.get(0).retTy();
                    if (rty.isEmpty())
                        nothings++;
                    else if (rty.isConcrete()) {
                        concrete++;
                        concretes.add(rty.toJulia());
                    } else
                        abstracts.add(rty.toJulia());
                }
            }
        }

        var s = "Got " + tot + " methods. " + count + " methods have results";
        s = weird > 0 ? s + " and " + weird + " methods had more than one result" : s;
        App.print(s);
        App.print("Found " + concrete + " stable methods, with " + concretes.size() + " unique return types");
        App.print("Found " + (count - concrete) + " un-stable methods with " + abstracts.size() + " unique return types. " + nothings + " methods that returned Union{}.");

        App.printSeparator();
        App.print("Abstract types: (printing the first few on terminal, remaining in the log file)");
        var max = 10;
        for (var a : abstracts) {
            if (max-- > 0)
                App.print("  " + a);
            else
                App.output("  " + a);
        }

        max = 10;
        App.printSeparator();
        App.print("Concrete types: (printing the first few on terminal, remaining in the log file)");
        for (var a : concretes) {
            if (max-- > 0)
                App.print("  " + a);
            else
                App.output("  " + a);
        }
        JuliaUtils.runConcretenessSanityChecks(ctxt.imports, ctxt.root.resolve("sanity.jl"), abstracts, concretes);
    }

    /**
     * A context remembers where this generator is writing to. We assume there will
     * be multiple instances working concurrently (at some point).
     *
     * If we ever get to multiple concurent tasks running in parallel, then each
     * should have its own context, with the same root but different tmp
     * directories. One will have to figure out what is private to each instance and
     * what can be shared across them.
     */
    class Context {

        Path root; // for all data procduced by this JVM
        Path tmp; // path to the temp directory that holds the Julia results e.g. /tmp/t0
        Path imports; // complete path for the file that has all the inmport statements
        Path tests; // complete path for the file that holds the code to run the tests
        List<String> testFiles = new ArrayList<>(); // the name of the test files created
        int count = 0; // how many fles we actually see in the /tmp/t0 dir after Julia has run
        ArrayList<String> failures = new ArrayList<>();

        /**
         * Create a context with a temporary directory.
         */
        public Context() {
            var dt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            try {
                var p = Paths.get("/tmp").resolve("jl_" + dt);
                App.print("Logging to " + p);
                if (Files.exists(p)) {
                    delete(p);
                }
                root = Files.createDirectory(p);
                p = p.resolve("out." + nextNum());
                tmp = Files.createDirectories(p);
            } catch (IOException e) {
                throw new Error(e); // deletion shouold not fail... without a valid context there is not much we can do
            }
            imports = root.resolve("imports.jl");
            tests = root.resolve("tests.jl");
        }

        /**
         * Recursively delete the contents of the directory
         *
         * @param path
         *                 to the directory to delete
         */
        final void delete(Path p) {
            try {
                // Recursively delete the contents of the directory
                Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes _a) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException _e) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new Error(e); // not sure what else but rethrow if we fail to delete, we could ignore.e..
            }
        }
    }

    /**
     * Create the packages file that list all the packages that were referenced in
     * the DB.
     */
    void createPkgs(Context ctxt) {
        var pkgs = new HashSet<String>();
        for (var p : it.names.packages) {
            if (p.startsWith("Base.") || p.startsWith("Core.") || p.startsWith("Pkg.") || p.equals("Base") || p.equals("Core") || p.equals("Pkg")) {
                continue;
            }
            var prefix = p.contains(".") ? p.substring(0, p.indexOf(".")) : p;
            pkgs.add(prefix);
        }
        try {
            try (var importsf = new BufferedWriter(new FileWriter(ctxt.imports.toString()))) {
                for (var p : pkgs) {
                    /* Silence possible import errors because of package extensions.
                     * If a package has an extension (ie. a module that gets loaded
                     * only when a set of other packages is loaded), we get prefixes
                     * coming from this extension. However, the extension is opaque
                     * to the user and cannot be imported manually.
                     * This supresses regular import errors, but those will just
                     * show later when we cannot find the given module in the test.
                     */
                    importsf.write("""
                        try import %s catch; println("imports.jl: Couldn't import `%s'") end
                        """.formatted(p, p));
                }
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    private static final String HEADER = """
            include("imports.jl")
            using InteractiveUtils
            InteractiveUtils.highlighting[:warntype] = false

            macro WARNTYPE(e1, e2, f)
              quote
                buffer = IOBuffer()
                try
                  code_warntype(IOContext(buffer, :color => false, :module => nothing, :compact => false), $(esc(e1)), $(esc(e2)))
                catch e
                  try
                    println(buffer, "Exception occurred: ", e)
                  catch e
                  end
               	end
               	open($(esc(f)), "w") do file
                  write(file, String(take!(buffer)))
               	end
              end
            end

            """;

    /**
     * Create stability tests for a list of requests. This generates a Julia program
     * that starts by importing all packages that are referenced in the DB, and then
     * defines the testing macro. The list of Test request is used to generate one
     * stability test per line.
     * 
     * The context remmebers the name of the files that will hold the results of
     * each stability test and the overall count of files. This is to check that all
     * the requested tests succeeded. (They could fail if there is a bug in our code
     * and that results in a Julia error.)
     */
    void createInitialTests(Context ctxt, List<Test> tests) {
        try {
            try (var w = new BufferedWriter(new FileWriter(ctxt.tests.toString()))) {
                w.write(HEADER);
                int cnt = 0;
                for (var t : tests) {
                    ctxt.testFiles.add(t.file);
                    cnt++;
                    w.write(t.toTest());
                }
                ctxt.count = cnt;
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    /**
     * Test hold information to generate one stability test: the name of the
     * function, the tuple of argument types, and the file in which to store the
     * results of code_warntype.
     */
    record Test(String name, String args, String file) {
        /**
         * Generate the Julia code for the stability test using the @WARNTYPE macro.
         * This consists of the name of the method, the arguments, and the string
         * containing the file name in which this will be outpput.
         */
        String toTest() {
            return """
                    @WARNTYPE %s [%s] "%s"
                    """.formatted(name, args, file);
        }
    }

    /**
     * Generate the requests for testing all the functions that have all concrete
     * argument types.
     */
    List<Test> testsForGroundSigs() {
        int cnt = 0;
        var tests = new ArrayList<Test>();
        for (var s : it.sigs.allSigs()) {
            if (s.isGround()) {
                var nm = "t" + cnt++ + ".tst";
                var t = ((Tuple) s.ty()).tys().stream().map(Type::toJulia).collect(Collectors.joining(", "));
                tests.add(new Test(juliaName(s), t, nm));
            }
        }
        return tests;
    }

    /**
     * Generate the requests for testing all the functions with arguments set to
     * Any. This will result in code_warntype returning all possible methods for the
     * requested function names.
     * 
     * This will be a good starting point, as we believe that functions that are
     * stable under Any are stable under more specific types.
     * 
     * Furthermore, we can use the internal type information (local variables and
     * called methods) as hints for future tests.
     */
    List<Test> testsForAllFuns() {
        int cnt = 0;
        var tests = new ArrayList<Test>();
        var seen = new HashSet<String>();
        for (var s : it.sigs.allSigs()) {
           // if (!s.nm().toString().endsWith("Base.count!")) continue; //TODO (comment out) used to filter what tests are generated during debugging            
            var signameArity = s.nm().toString() + s.arity();
            if (seen.contains(signameArity)) continue;
            seen.add(signameArity);
            var nm = "t" + cnt++ + ".tst";
            var anys = new ArrayList<Type>();
            for (var i = 0; i < s.arity(); i++)
                anys.add(it.any);
            var t = anys.stream().map(Type::toJulia).collect(Collectors.joining(", "));
            tests.add(new Test(juliaName(s), t, nm));
        }
        return tests;
    }

    /**
     * Read the results of the tests as genereated by Julia.
     */
    void readResults(Context ctxt) {
        File dir = ctxt.tmp.toFile();
        FilenameFilter filter = (file, name) -> name.endsWith(".tst");
        File[] files = dir.listFiles(filter);
        if (files == null) {
            App.print("Directory does not exist. Something is wrong.");
            return;
        }
        if (files.length != ctxt.count) App.print("Expected " + ctxt.count + " files, found " + files.length);
        int count = 0;
        for (File file : files) {
            if (readFile(file))
                count++;
            else
                ctxt.failures.add(file.toString());
        }
        if (count != ctxt.count) App.print("Expected " + ctxt.count + " methods, found " + count);
        if (!ctxt.failures.isEmpty()) {
            var strings = new ArrayList<String>();
            var empties = new ArrayList<String>();
            App.print("The following " + ctxt.failures.size() + " files contain failed requests");
            for (var fn : ctxt.failures) {
                BufferedReader rd = null;
                try {
                    rd = new BufferedReader(new FileReader(fn));
                    var ln = rd.readLine();
                    if (ln == null)
                        empties.add(fn);
                    else
                        strings.add(fn + " : " + ln);
                } catch (IOException e) {
                } finally {
                    try {
                        rd.close();
                    } catch (IOException e) {
                    }
                }
            }
            Collections.sort(strings);
            for (var s : strings)
                App.print("  " + s);
            Collections.sort(empties);
            for (var s : empties)
                App.print("  " + s);
        }
    }

    /**
     * A file produced by code_warntype may contain results for zero, one or more
     * methods. Zero means code_warntype failed, one is the usual case, and more
     * than one means the types passed applied to multiple methods (We start by
     * sending Any to all arguments, so we expect this outcome).
     * 
     * Parse the results, and attempt to attribute them to signatures that are
     * stored in the DB. Each result should have a signature in the DB. If not: then
     * our method discovery missed something.
     * 
     * Returns true if the file yielded results.
     */
    private boolean readFile(File file) {
        try {
            var ms = MethodInformation.parse(new Parser().withFile(file).lex(), file.toString());
            if (ms.isEmpty()) return false;
            for (var m : ms)
                readOneSigResult(m);
            return true;
        } catch (Exception e) {
            App.output("Error parsing " + file.toString() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * The argument `m` is a method extracted from the results of code_warntype.
     * This method queries the DB by method name to obtain a List of Signatures.Info
     * objects that contain all the methods with the same name in the DB. The
     * equalsSig method is used to filter that list to the methods that have the
     * same origin package and file and line number. There should be one. The result
     * is attributed to it.
     * 
     * Otherwise: If there is no match, this may be because we missed a method (e.g.
     * are not analyzing all the method loaded into Julia. If there were more than
     * one, this is likely a bug. But one that we choose to ignore as the price of
     * discarding the information from code_warntype.
     */
    private void readOneSigResult(Method m) {
        //App.print(m.sig + " -> " + m.returnType); // Print all signatures  
        var siginfo = it.sigs.get(m.sig.nm().operationName());
        if (siginfo == null) {
            App.print("Function " + m.sig.nm() + " not in DB. Odd that a test was generated for it");
            return;
        }
        var foundsigs = siginfo.stream().filter(sig -> sig.equalsSigFromCWT(m.sig, m.originPackageAndFile)).collect(Collectors.toList());
        if (foundsigs.size() == 0)
            App.print("No match for " + m.sig + ". This can happen if processing a susbet of methods.");
        else if (foundsigs.size() > 1) {
            App.print("Found " + foundsigs.size() + " matches for " + m.sig + ". This sounds like a bug.");
        } else
            foundsigs.getFirst().addResult(m.sig.ty(), m.returnType);
    }

    /** Convert a Sig's name to a Julia name. */
    String juliaName(Sig s) {
        return s.nm().toJulia();
    }
}
