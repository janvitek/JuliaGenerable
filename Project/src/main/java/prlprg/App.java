package prlprg;

public class App {

    static boolean debug = false;
    static GenDB db = new GenDB();
    static String dir, types, functions;
    static String[] defaultArgs = {//"-d", // run micro tests
        "-c LIGHT", // color the output
        "-r ../Inputs/", // root directory with input files
        "-f raicode_functions.jlg", // file with function signatures
        "-t raicode_types.jlg"}; // file with type declarations

    public static void main(String[] args) {
        parseArgs(args);
        var p = new Parser();
        p = debug ? p.withString(tstr) : p.withFile(dir + types);
        p.addLines(addType); // adding some builtin types (see below)
        p.tokenize();
        while (!p.peek().isEOF()) {
            db.addTyDecl(TypeDeclaration.parse(p).toTy());
        }
        p = new Parser();
        p = debug ? p.withString(str) : p.withFile(dir + functions);
        p.tokenize();
        while (!p.peek().isEOF()) {
            db.addSig(Function.parse(p).toTy());
        }
        db.cleanUp();
        new Generator(db).gen();
    }

    /// TODO: this is not supported struct R{v, a, *, vv, Tuple} <: Function end
    /// Currenetly, we only support types that have variables in the arguments. It is unclear why one would
    /// want to have '*' and 'Tuple'
    //
    // TODO: this is not supported
    //    struct C{T<:Tuple{}, A<:B{Tuple{}}} <: B{T<:Tuple{}} end (from)
    // RHS has a bound we support
    //   struct C{T<:Tuple{}, A<:B{Tuple{}}} <: B{T} end (from)
    static String tstr = """
      struct A{T<:B{<:B}} <: B end
      struct C{T<:Tuple{}, A<:B{Tuple{}}} <: B{T} end (from)
      abstract type R <: Vector{Val{:el}} end
      struct D{T, S, E} <: B{E} end (asdsa)
    """;
    static String str = """
     function a(x::N{K, V, E}, y::Union{BV1{K, V, E}, BV2{K, V, E}} where {K, V, E}, w::Union{B1{K, V, E}, B2{K, V, E}}) where {K, V, E}
     function _tuplen(t::Type{<:Tuple}) in RAICode.QueryEva...uQQtils.jl:103 (method for generic function _tuplen)
     function f() @  asda/asds
     function ch(A::Stride{v } where v<:Union{  ComplexF64}, ::Type{LUp })
    """;

    static void parseArgs(String[] args) {
        if (args.length == 0) {
            args = defaultArgs;
        }
        for (var arg : args) {
            if (arg.equals("-d")) { // debug
                debug = true;
            } else if (arg.startsWith("-r ")) {  // root directory
                dir = arg.substring(3).strip();
            } else if (arg.startsWith("-t ")) {
                types = arg.substring(3).strip();
            } else if (arg.startsWith("-f ")) {
                functions = arg.substring(3).strip();
            } else if (arg.startsWith("-c ")) { // Color mode
                CodeColors.mode = switch (arg.substring(3).strip()) {
                    case "DARK" ->
                        CodeColors.Mode.DARK;
                    case "LIGHT" ->
                        CodeColors.Mode.LIGHT;
                    default ->
                        CodeColors.Mode.NONE;
                };
            } else {
                System.err.println("Unknown argument: " + arg);
                System.exit(1);
            }
        }
    }
    // add any type declarations that cannot be found or patched.
    static String addType = """
        abstract type Any end
        """;
}
