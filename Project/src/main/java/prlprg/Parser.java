package prlprg;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import prlprg.App.Timer;
import prlprg.NameUtils.FuncName;
import prlprg.NameUtils.TypeName;

/**
 * Parser for Julia type and method signatures, and files from code_warntype.
 * The inputs are well-behaved records, one per line.
 */
class Parser {

    /**
     * Interface for types as read by the parser. As it can't differentiate types
     * from variables, there is some approximation in what it returns, later phases
     * sharpen the types and clean up the slop.
     */
    interface ParsedType {

        /** Creates a type in a simpler format */
        Ty toTy();

        /** Read a type name. */
        static TypeName parseTypeName(Parser p) {
            var nm = p.take().toString();
            if (nm.isEmpty()) p.failAt("Missing type name", p.peek());
            return GenDB.it.names.type(nm);
        }

        /** Function names are dotted identifiers. */
        static FuncName parseFunctionName(Parser p) {
            var str = p.take().toString();
            if (str.isEmpty()) p.failAt("Missing function name", p.peek());
            return GenDB.it.names.function(str);
        }
    }

    /** An instance of a datatype constructor (not a union all or bound var). */
    record TypeInst(TypeName nm, List<ParsedType> ps) implements ParsedType {

        /** Either returns a type or a constant */
        static ParsedType parse(Parser p) {
            var constant = Constant.parseConstant(p);
            if (constant != null) return constant;
            var name = ParsedType.parseTypeName(p).resolve(); // Resolve will deal with soft imports...
            if (!p.has("{")) return new TypeInst(name, null); // null denotes absence of type params.
            List<ParsedType> params = new ArrayList<>();
            var q = p.sliceMatchedDelims("{", "}");
            while (!q.isEmpty())
                params.add(BoundVar.parse(q.sliceNextCommaOrSemi()));
            return new TypeInst(name, params);
        }

        /**
         * Turns a ParsedType to a Ty. This cleans up tuples and unions. Note the
         * difference between:
         * 
         * <pre>
         *   Tuple{Int} 
         *   Tuple{}
         *   Tuple
         * </pre>
         * 
         * The first two will be Tuple types, while the last one will be returned as a
         * TyInst{"Tuple"}. The last one is an existential standing for any kind of
         * tuple. The same hold for Union.
         */
        @Override
        public Ty toTy() {
            if (ps == null) return new TyInst(nm, new ArrayList<>());
            var tys = ps.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
            return nm.isTuple() ? new TyTuple(tys) : nm.isUnion() ? new TyUnion(tys) : new TyInst(nm, tys);
        }

        @Override
        public String toString() {
            return nm + (ps == null ? "" : "{" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + "}");
        }

    }

    record Constant(String val) implements ParsedType {

        static Constant parseConstant(Parser p) {
            var tryit = tryParse(p.copy());
            return tryit == null ? null : tryParse(p);
        }

        private static Constant tryParse(Parser p) {
            var tok = p.take();
            if (tok.isNumber() || tok.isString())
                return new Constant(tok.toString());
            else if (tok.isIdent()) {
                var s = tok.toString();
                if (s.equals("true") || s.equals("false") || s.equals("missing") || s.equals("nothing"))
                    return new Constant(s);
                else if (p.has("("))
                    return new Constant(s + "(" + p.sliceMatchedDelims("(", ")").foldToString("") + ")");
                else
                    return null;
            } else if (tok.isChar(':')) {
                var s = tok.toString();
                if (p.has("("))
                    s += "(" + p.sliceMatchedDelims("(", ")").foldToString("") + ")";
                else if (p.has("."))
                    s = s + p.take() + p.take();
                else
                    s += p.take();
                return new Constant(s);
            } else
                return null;
        }

        public Ty toTy() {
            return new TyCon(val);
        }

        @Override
        public String toString() {
            return val;
        }

    }

    /**
     * A variable with optional upper and lower bounds. At this point we are still a
     * little shaky on which is which, so we parse all of them to ParsedType. Also,
     * if we get an implicit variable name, we create a fesh name for it.
     */
    record BoundVar(String name, ParsedType lower, ParsedType upper) implements ParsedType {

        /**
         * Parses something that could be a bound var.
         * 
         * <pre>
         *    T                ==> return T
         *    T <: U           ==> return BoundVar(T, null, U)
         *    L <: T <: U      ==> return BoundVar(T, L, U)
         *    T >: L           ==> return BoundVar(T, L, null)
         *    >: L             ==> return BoundVar(fresh(), L, null)
         * </pre>
         */
        static ParsedType parse(Parser p) {
            if (p.has("<:") || p.has(">:")) {
                var lt = p.has("<:");
                var t = UnionAllInst.parse(p.drop());
                return lt ? new BoundVar(NameUtils.fresh(), null, t) : new BoundVar(NameUtils.fresh(), t, null);
            }
            var t = UnionAllInst.parse(p);
            if (p.has("<:")) {
                var u = UnionAllInst.parse(p.drop());
                return p.has("<:") ? new BoundVar(UnionAllInst.parse(p.drop()).toString(), t, u) : new BoundVar(t.toString(), null, u);
            } else if (p.has(">:")) {
                return p.isEmpty() ? new BoundVar(NameUtils.fresh(), t, null) : new BoundVar(t.toString(), UnionAllInst.parse(p.drop()), null);
            } else
                return t;
        }

        /**
         * Create a TyVar from a BoundVar.
         */
        @Override
        public Ty toTy() {
            return new TyVar(name, lower == null ? Ty.none() : lower.toTy(), (upper == null || upper.toString().equals("Any")) ? Ty.any() : upper.toTy());
        }

        @Override
        public String toString() {
            return (lower != null ? lower + " <: " : "") + name + (upper != null ? " <: " + upper : "");
        }
    }

    /**
     * Parse what could be a union all type. Expressions such as:
     * 
     * <pre>
     * Int{X} where {Z <: X <: Y, Y <: Int} where {X <: Int}
     * </pre>
     * 
     * can be handled.
     */

    record UnionAllInst(ParsedType body, List<ParsedType> bounds) implements ParsedType {

        /**
         * Parse a union all type.
         * 
         * Our input format did change over time, I have seen extraneous parens. Such
         * as:
         * 
         * <pre>
         *    U <: (AbstractVector)
         * </pre>
         * 
         * where the parens could be removed.
         * 
         * On the other hand:
         * 
         * <pre>
         *  ()
         * </pre>
         * 
         * is the empty tuple.
         * 
         * There can also be bracces around the parameters, such as:
         * 
         * <pre>
         *  Vector{T} where { T <: Int }
         * </pre>
         * 
         * these too can be ignored.
         */
        static ParsedType parse(Parser p) {
            if (p.has("(")) {
                p = p.sliceMatchedDelims("(", ")");
                if (p.isEmpty()) return new TypeInst(GenDB.it.names.tuple(), new ArrayList<>());
            }
            var type = TypeInst.parse(p);
            if (!p.has("where")) return type;
            if (p.take("where").has("{")) p = p.sliceMatchedDelims("{", "}");
            var boundVars = new ArrayList<ParsedType>();
            while (!p.isEmpty()) {
                boundVars.add(BoundVar.parse(p));
                if (p.has(","))
                    p.drop().failIfEmpty("Missing type parameter", p.peek());
                else
                    break;
            }
            return new UnionAllInst(type, boundVars);
        }

        /**
         * Converting a UnionallInst to possibly multiple TyExist. The main difference
         * between the two is that a TyExist has a single bound.
         */
        @Override
        public Ty toTy() {
            var ty = body.toTy();
            var it = bounds.listIterator(bounds.size());
            while (it.hasPrevious()) // Going backwards, building Exists inside out
                ty = new TyExist(it.previous().toTy(), ty);
            return ty;
        }

        @Override
        public String toString() {
            return body.toString() + (bounds.isEmpty() ? "" : " where " + bounds.stream().map(Object::toString).collect(Collectors.joining(", ")));
        }
    }

    /**
     * A type declaration fresh from the parser.
     */
    record TypeDeclaration(String modifiers, TypeName nm, List<ParsedType> ps, TypeInst parent, String src) {

        /**
         * Parse the modifiers, such as abstract, primitive, etc. This does not sanity
         * check them, we expect well formed input.
         */
        static String parseModifiers(Parser p) {
            var str = "";
            while (p.has("abstract") || p.has("primitive") || p.has("struct") || p.has("type") || p.has("mutable"))
                str += p.take().toString() + " ";
            return str;
        }

        /**
         * Parses a declaration. The format comes from the ouput of the JanJ's tool.
         */
        static TypeDeclaration parse(Parser p) {
            NameUtils.reset(); // reset the fresh variable generator
            var modifiers = parseModifiers(p);
            var name = ParsedType.parseTypeName(p); // do not call resolve
            var src = p.last.getLine();
            var ps = new ArrayList<ParsedType>();
            var q = p.sliceMatchedDelims("{", "}");
            while (!q.isEmpty())
                ps.add(BoundVar.parse(q.sliceNextCommaOrSemi()));

            TypeInst parent = p.has("<:") ? (TypeInst) TypeInst.parse(p.drop()) : new TypeInst(GenDB.it.names.any(), new ArrayList<>());
            if (p.peek().isNumber()) {
                p.drop();
                if (!modifiers.contains("primitive")) p.failAt("Expected 'primitive'", p.peek());
            }
            p.take("end");
            return new TypeDeclaration(modifiers, name, ps, parent, src);
        }

        /**
         * Turns a TypeDeclaration to a TyDecl.
         */
        TyDecl toTy() {
            var args = ps.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
            return new TyDecl(modifiers, nm, new TyInst(nm, args), parent.toTy(), src);
        }

        @Override
        public String toString() {
            return modifiers + nm + (ps.isEmpty() ? "" : ("{" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + "}")) + (parent == null ? "" : (" <: " + parent));
        }
    }

    /**
     * A Param has name and a type, furthermore a bool indicates if this is a
     * vararg.
     */
    record Param(String nm, ParsedType ty, boolean varargs) {

        // can be:  x   |  x :: T   |  :: T   |   x...  |   :: T...
        static Param parse(Parser p) {
            var nm = p.has("::") ? "?NA" : p.take().toString();
            var tok = p.take();
            var type = tok.is("::") ? UnionAllInst.parse(p) : null;
            var varargs = p.has("...") ? p.take().is("...") : false;
            if (type == null && p.has("::")) {
                type = UnionAllInst.parse(p.drop());
                varargs = p.has("...") ? p.take().is("...") : false;
            }
            // FIXME: this is a hack somethign is wrong with the parser
            if (nm.endsWith("...")) {
                nm = nm.substring(0, nm.length() - 3);
                varargs = true;
            }
            return new Param(nm, type, varargs);
        }

        /** Wrap the type in a vararg if needed. */
        Ty toTy() {
            var t = ty == null ? Ty.any() : ty.toTy();
            if (!varargs) return t;
            List<Ty> tt = new ArrayList<>();
            tt.add(t);
            return new TyInst(GenDB.it.names.type("Core.Vararg"), tt);
        }

        @Override
        public String toString() {
            return nm + (ty != null ? " :: " + ty.toString() : "") + (varargs ? "..." : "");
        }
    }

    /**
     * A function declaration of the form
     *
     * <pre>
     * function foo(x::T, y::T) where {T <: Int}
     * </pre>
     * 
     * The field kwPos says where the first keyword argument is, if any. -1 means
     * none.
     */
    record Function(FuncName nm, List<Param> ps, List<ParsedType> wheres, int kwPos, String src) {

        static List<ParsedType> parseWhere(Parser p) {
            var wheres = new ArrayList<ParsedType>();
            if (!p.has("where")) return wheres;
            if (p.take("where").has("{")) p = p.sliceMatchedDelims("{", "}"); // we only need to read the where clause
            while (!p.isEmpty()) {
                wheres.add(BoundVar.parse(p));
                if (p.has(","))
                    p.drop().failIfEmpty("Missing type parameter", p.peek());
                else if (p.has("[")) break;// comment with line/file info
            }
            return wheres;
        }

        static Function parse(Parser p) {
            NameUtils.reset();
            if (p.has("function")) p.drop();// keyword is optional
            var name = ParsedType.parseFunctionName(p);
            var source = p.last.getLine();
            var q = p.sliceMatchedDelims("(", ")");
            var params = new ArrayList<Param>();
            var semiOrNull = q.getSemi();
            var firstKeyword = -1;
            while (!q.isEmpty()) {
                var r = q.sliceNextCommaOrSemi();
                if (r.isEmpty()) break;
                var tok = r.peek();
                if (semiOrNull != null && semiOrNull.start() < tok.start() && firstKeyword == -1) firstKeyword = params.size();
                params.add(Param.parse(r));
            }
            var wheres = parseWhere(p);
            /// There may be leftovers...
            return new Function(name, params, wheres, firstKeyword, source);
        }

        TySig toTy() {
            List<Ty> tys = ps.stream().map(Param::toTy).collect(Collectors.toList());
            List<String> names = ps.stream().map(Param::nm).collect(Collectors.toList());
            var reverse = wheres.reversed();
            Ty nty = new TyTuple(tys);
            for (var where : reverse)
                nty = new TyExist(where.toTy(), nty);
            return new TySig(nm, nty, names, kwPos, src);
        }

        @Override
        public String toString() {
            return nm + "(" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")" + (wheres.isEmpty() ? "" : (" where " + wheres.stream().map(Object::toString).collect(Collectors.joining(", "))));
        }
    }

    record TypeAlias(TypeName tn, Ty sig) {

        static TypeAlias parseAlias(Parser p) {
            NameUtils.reset();
            try {
                var name = ParsedType.parseTypeName(p.take("const")); // do not call resolves
                var uai = UnionAllInst.parse(p.take("="));
                return new TypeAlias(name, uai.toTy()); // There are leftover that we ignore
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Holds the information obtained from code_warntype, this is slightly more raw
     * than needed, we then build the Method class from this.
     *
     * This is an approximate parser, due to time constraints it will not parse
     * every bit of a method information, the idea is that even partial information
     * can be of use. Over time, we can revisit and get more to parse. Some of it
     * may be easy other stuff not so much.
     */
    static class MethodInformation {

        Sig decl;
        Sig originDecl;
        List<Type> staticArguments;
        List<Type> arguments;
        List<String> argnames;
        List<Type> locals;
        List<String> locnames;
        Type returnType;
        String src;
        List<Op> ops = new ArrayList<>();
        List<TySig> sigs = new ArrayList<>();

        /** Parse a method information block as returned by code_warntype. **/
        static List<Method> parse(Parser p, String filename) {
            var ms = new ArrayList<Method>();
            while (!p.isEmpty()) {
                var mi = new MethodInformation();
                mi.parseHeader(p);
                mi.parseBody(p);
                ms.add(new Method(mi, filename));
            }
            return ms;
        }

        private void parseHeader(Parser p) {
            p.take("MethodInstance").take("for");
            this.decl = parseFun(p.sliceLine());
            var q = p.sliceLine().take("from");
            this.originDecl = parseFun(q.sliceUpToLast("@"));
            this.src = q.foldToString(" ").trim();
            try {
                // This section starts with "Static Parameters" and then introduces one type
                // variable per line. To return a List of Type object is slightly awkward
                // because bound variables are not types. We encode them into an empty
                // existential type -- this is a hack.
                var bs = new ArrayList<Type>();
                if (p.has("Static")) {
                    p.nextLine();
                    while (!p.isEmpty() && !p.has("Arguments") && !p.has("Locals") && !p.has("Body")) {
                        var ty = BoundVar.parse(p.sliceLine()).toTy().fixUp(new ArrayList<>());
                        bs.add(new TyExist(ty, Ty.any()).toType(new ArrayList<>()));
                    }
                }
                this.staticArguments = bs;
            } catch (Exception e) {
                App.output("Error parsing static arguments: " + e.getMessage());
            }
            try {
                var partys = new ArrayList<Type>();
                var parnms = new ArrayList<String>();
                if (p.has("Arguments")) {
                    p.nextLine();
                    while (!p.isEmpty() && !p.has("Locals") && !p.has("Body")) {
                        var param = Param.parse(p.sliceLine());
                        partys.add(param.toTy().fixUp(new ArrayList<>()).toType(new ArrayList<>()));
                        parnms.add(param.nm());
                    }
                }
                this.arguments = partys;
                this.argnames = parnms;
            } catch (Exception e) {
                App.output("Error parsing arguments: " + e.getMessage());
            }
            try {
                var partys = new ArrayList<Type>();
                var parnms = new ArrayList<String>();

                if (p.has("Locals")) {
                    p.nextLine();
                    while (!p.isEmpty() && !p.has("Body")) {
                        var param = Param.parse(p.sliceLine());
                        partys.add(param.toTy().fixUp(new ArrayList<>()).toType(new ArrayList<>()));
                        parnms.add(param.nm());
                    }
                }
                this.locals = partys;
                this.locnames = parnms;
            } catch (Exception e) {
                App.output("Error parsing locals: " + e.getMessage());
            }
            try {
                p.take("Body").take("::");
                var ty = UnionAllInst.parse(p).toTy().fixUp(new ArrayList<>());
                this.returnType = ty.toType(new ArrayList<>());
            } catch (Exception e) {
                App.output("Error parsing return type: " + e.getMessage());
            }
        }

        private Sig parseFun(Parser p) {
            var d = Function.parse(p).toTy().fixUp();
            return new Sig(d.nm(), d.ty().toType(new ArrayList<>()), d.argnms(), d.kwPos(), d.src());
        }

        private Op parseOp(Parser p) {
            if (p.has("(")) {
                var q = p.sliceMatchedDelims("(", ")");
                if (p.has("(")) {
                    // Using a register as the function name
                    var nm = q.take().toString();
                    var args = callArglist(p.sliceMatchedDelims("(", ")"));
                    return new RegAssign(nm, GenDB.it.names.function(nm.toString()), args, null);
                } else {
                    // E.g. │         (tn = Core.Compiler.getproperty(%3, :name))
                    var tgt = p.take().toString();
                    var op = normalCall(p.take("="));
                    return new RegAssign(tgt, op.op, op.args, op.ret);
                }
            } else
                return p.peek().toString().startsWith("%") ? parseRegisterAssign(p) : normalCall(p);
        }

        private List<String> callArglist(Parser p) {
            var args = new ArrayList<String>();
            while (!p.isEmpty())
                args.add(p.sliceNextCommaOrSemi().take().toString());
            return args;
        }

        // E.g. |   %15 = Core.Compiler.getglobal(top, name)::Any
        // E.g. │   %16 = (f === %15)::Bool
        private Op parseRegisterAssign(Parser p) {
            var reg = p.take().toString();
            if (p.take("=").has("(")) {
                var q = p.sliceMatchedDelims("(", ")");
                var l = q.take().toString();
                var op = q.take().toString();
                var r = q.take().toString();
                var func = GenDB.it.names.function(op);
                if (!q.isEmpty()) throw new RuntimeException("Leftovers " + q);
                var ret = UnionAllInst.parse(p.take("::"));
                return new RegAssign(reg, func, List.of(l, r), ret);
            } else {
                var op = normalCall(p);
                return new RegAssign(reg, op.op, op.args, op.ret);
            }
        }

        // E.g. │         Core.NewvarNode(:(top))
        private Call normalCall(Parser p) {
            var nm = ParsedType.parseFunctionName(p);
            var q = p.sliceMatchedDelims("(", ")");
            var args = callArglist(q);
            var r = p.has("::") ? UnionAllInst.parse(p.take("::")) : null;
            return new Call(nm, args, r);
        }

        /** Operation in the code_warntype instruction stream. A single line of code. */
        interface Op {
        }

        /** An instruction that assigns to a register. */
        record RegAssign(String tgt, FuncName op, List<String> args, ParsedType ret) implements Op {

            @Override
            public String toString() {
                return tgt + " = " + op + "(" + args.stream().collect(Collectors.joining(", ")) + ")" + (ret == null ? "" : " :: " + ret);
            }
        }

        record Call(FuncName op, List<String> args, ParsedType ret) implements Op {

            @Override
            public String toString() {
                return op + "(" + args.stream().collect(Collectors.joining(", ")) + ")" + (ret == null ? "" : " :: " + ret);
            }
        }

        record Other(String val) implements Op {

            @Override
            public String toString() {
                return val;
            }
        }

        private void parseBody(Parser p) {
            while (!p.isEmpty()) {
                if (p.has("MethodInstance")) return; // there can be more than one MethodInstance per file
                // when code_warntype is given arguments such as Any that may cover multiple methods.
                var q = p.sliceLine();
                var last = q.peek();
                try {
                    // Start by dropping the decoration that is added for legivility by code_warntype
                    if (q.peek().isNumber())
                        q.drop().drop();
                    else if (q.peek().is("│"))
                        q.drop();
                    else if (q.peek().toString().startsWith("└"))
                        q.drop();
                    else if (q.peek().is("@") || q.peek().is("["))
                        continue;
                    else {
                        ops.add(new Other("Unparsed :: " + q.peek().getLine()));
                        continue;
                    }
                    q.failIfEmpty("Expected more...", last);

                    var tok = q.peek();
                    if (tok.is("goto") || tok.is("return") || tok.is("nothing"))
                        ops.add(new Other(q.take().toString()));
                    else
                        ops.add(parseOp(q));
                } catch (Throwable e) {
                    App.output("Error parsing op: " + last.getLine());
                }
            }
        }

        @Override
        public String toString() {
            var s1 = decl.toString() + "\n" + (staticArguments.isEmpty() ? "" : "staticArguments: " + staticArguments + "\n") + (arguments.isEmpty() ? "" : "arguments: " + arguments + "\n");
            var s2 = (locals.isEmpty() ? "" : "locals: " + locals + "\n") + "returnType: " + returnType + "\n";
            var s3 = "src: " + src + "\n" + ops.stream().map(Object::toString).collect(Collectors.joining("\n"));
            return s1 + s2 + s3;
        }

    }

    Tok last; // for debugging purposes, we try to keep the last token, so we can remember where an error occured
    private Lexer lex = new Lexer();
    List<Tok> toks = new ArrayList<>();
    private List<String> sourceText = new ArrayList<>();

    /** Add lines of input to the parser. */
    Parser withLines(String[] lines) {
        sourceText.addAll(Arrays.asList(lines));
        return this;
    }

    /**
     * Initialiszes the parser with data held in a file.
     */
    Parser withFile(Object pathobj) {
        var path = pathobj.toString();
        try {
            sourceText.addAll(Files.readAllLines(Path.of(path)));
            return this;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Run the lexer. Call when all the input has been provided */
    Parser lex() {
        lex = new Lexer(sourceText.toArray(new String[0]));
        toks = lex.next();
        stats.lines = sourceText.size();
        return this;
    }

    /** Return a copy of this parser with the current line and an empty lexer. */
    Parser copy() {
        var p = new Parser();
        p.lex = lex;
        p.toks = new ArrayList<>(toks);
        p.last = last;
        p.sourceText = sourceText;
        return p;
    }

    /**
     * Assumes that we are parsing a file that has one method signature per line,
     * parse signature up to `max` and add them to the DB.
     * 
     * @param max
     *                the maximum number of signatures to rea. Used in development
     *                to limit the number of signatures to process.
     */
    void parseSigs(int max) {
        stats.linesOfSigs = stats.lines;
        stats.sigs.start();

        while (!isEmpty() && max-- > 0)
            GenDB.it.addSig(Function.parse(sliceLine()).toTy().fixUp());

        stats.sigs.stop();
        stats.sigsFound = GenDB.it.sigs.allSigs().size();
        stats.funsFound = GenDB.it.sigs.allNames().size();
        App.print("Processed " + stats.linesOfSigs + " lines of input, found " + stats.sigsFound + " sigs for " + stats.funsFound + " generic functions in " + stats.sigs);
    }

    /**
     * Split current parser into one with the current line, the old one has the rest
     * of the tokens.
     */
    Parser sliceLine() {
        var p = new Parser();
        p.toks = toks;
        toks = lex.next();
        return p;
    }

    /** Advance to the next line, dropping any leftover tokens. Return this. */
    Parser nextLine() {
        toks = lex.next();
        return this;
    }

    /**
     * Assumes that we are parsing a file that has one type declaration per line,
     * parse every declaration and add it to the DB.
     */
    void parseTypes() {
        stats.linesOfTypes = stats.lines;
        stats.types.start();

        while (!isEmpty())
            GenDB.it.types.addParsed(TypeDeclaration.parse(sliceLine()));

        stats.types.stop();
        stats.typesFound = GenDB.it.types.all().size();
        App.print("Processed " + stats.linesOfTypes + " lines of input, found " + stats.typesFound + " types in " + stats.types);
    }

    /** First pass on the type declaration file, only read the type names. s */
    void parseTypeNames() {
        while (!isEmpty()) {
            var p = sliceLine();
            TypeDeclaration.parseModifiers(p);
            GenDB.it.names.addTypeDef(GenDB.it.names.type(p.take().toString()));
        }
    }

    /**
     * Assumes that we are parsing a file that has one type declaration per line,
     * parse every declaration and add it to the DB.
     */
    void parseAliases() {
        stats.linesOfAliases = stats.lines;
        stats.aliases.start();
        var aliases = GenDB.it.aliases;

        while (!isEmpty()) {
            var q = sliceLine();
            var ln = q.copy().foldToString(" ");
            if (!ln.contains("typ alias")) continue;
            var a = TypeAlias.parseAlias(q);
            if (a != null) aliases.addParsed(a.tn(), a.sig());
        }

        stats.aliases.stop();
        stats.aliasesFound = GenDB.it.types.all().size();
        App.print("Processed " + stats.linesOfAliases + " lines of input, found " + stats.aliasesFound + " types in " + stats.aliases);
    }

    /** First pass on the Alias file, read only the name of aliases. */
    void parseAliasNames() {
        while (!isEmpty()) {
            var q = sliceLine();
            if (!q.copy().foldToString(" ").contains("typ alias")) continue;
            q.take("const");
            GenDB.it.names.addAliasDef(GenDB.it.names.type(q.take().toString()));
        }
    }

    final Stats stats = new Stats();

    /** Various statistics for reporting information on the command line. */
    class Stats {
        int lines;
        int linesOfSigs;
        int sigsFound;
        int funsFound;
        int linesOfTypes;
        int typesFound;
        int aliasesFound;
        int linesOfAliases;
        Timer aliases = new Timer();
        Timer sigs = new Timer();
        Timer types = new Timer();
    }

    /**
     * Splits the current parser in two, the prefix goes to the new parser and the
     * tail is left in the old parser. Consider
     * 
     * <pre>
     *   "("  "x"  ","  "y" ")" "::" "Int"
     * </pre>
     * 
     * If we request to slice from "(" up to ")" the new parser will contain
     * 
     * <pre>
     *  "x" , "y" 
     * <pre>
     * and the current parser will be left with 
     * <pre>
     *   "::" "Int"
     * </pre>
     * 
     * The matching delims are elided. This is smart enough to deal with nesting.
     */
    Parser sliceMatchedDelims(String s, String e) {
        var p = new Parser();
        if (!has(s)) return p; // empty slice

        drop();
        var count = 1; // we have seen 1 starting delimiter
        while (!(count == 1 && has(e))) { // stop when next token is matching close
            var tok = take();
            p.add(tok);
            if (tok.is(s)) {
                count++;
            } else if (tok.is(e)) {
                count--;
            } else if (tok.isEOF()) {
                failAt("Missing '" + e + "'", last);
            }
        }
        drop();
        return p; // leading s and trailing e not included
    }

    /**
     * Splits the current parser in two. The new parser has everything up to the
     * next `,` or `;`. The current parser retains everything after. The delimiter
     * is dropped.
     * 
     * THis makes sense after one has sliced the "(" and ")" around an argument
     * list.
     */
    Parser sliceNextCommaOrSemi() {
        var p = new Parser();
        var count = 0;
        while (!isEmpty()) {
            var tok = take();
            p.add(tok);
            if (tok.is("(") || tok.is("{")) {
                count++;
            } else if (tok.is(")") || tok.is("}")) {
                count--;
            }
            if (count == 0 && (has(",") || has(";"))) {
                break;
            }
        }
        if (has(",") || has(";")) drop(); // don't drop if empty
        if (count != 0) failAt("Bracketing went wrong while looking for  , or ;", last);
        return p;
    }

    /**
     * Return the next semi colon, found at the same syntactic level. Return null if
     * none there.
     */
    Tok getSemi() {
        var p = copy();
        var count = 0;
        while (!p.isEmpty()) {
            var tok = p.take();
            if (tok.is("(") || tok.is("{"))
                count++;
            else if (tok.is(")") || tok.is("}")) count--;

            if (count == 0 && p.has(";")) {
                return p.take();
            }
        }
        return null;
    }

    /**
     * Splits this parser in two. Everything up to the last occurrence of `s` in the
     * new parser, everything after in the old one.
     * 
     * For example, if we know that a line is terminated by `@ followed by some
     * tokens.
     * 
     * Fail if there is no occurence of `s`
     */
    Parser sliceUpToLast(String s) {
        var p = new Parser();
        if (isEmpty()) return p;
        int pos = lastIndexOf(s);
        if (pos == -1) throw new RuntimeException("Failed to slice " + s + " not found");
        while (pos-- > 0)
            p.add(take());
        take(s);
        return p;
    }

    /** Index of last occurence of `s`, or -1 */
    private int lastIndexOf(String s) {
        int i = toks.size() - 1;
        while (i >= 0)
            if (toks.get(i).is(s))
                return i;
            else
                i--;
        return -1;
    }

    /** True if there are no more tokens in this parser. */
    boolean isEmpty() {
        return toks.isEmpty() || (toks.size() == 1 && toks.get(0).isEOF());
    }

    /** Is string `s` the current value. */
    boolean has(String s) {
        return peek().is(s);
    }

    /** Return the end of file token. Used as EOL */
    Tok eof() {
        return new Tok(null, Kind.EOF, 0, 0, 0);
    }

    /** Return current token without consuming it or EOF. */
    Tok peek() {
        return last = !toks.isEmpty() ? toks.get(0) : eof();
    }

    /** Consume a token, return current parser. */
    Parser drop() {
        toks.remove(0);
        return this;
    }

    /** Return and consume a token, or EOF if empty. */
    Tok take() {
        return !toks.isEmpty() ? toks.remove(0) : eof();
    }

    /** Consume current token, fail if not `s`. */
    Parser take(String s) {
        if (isEmpty()) throw new RuntimeException("Expected " + s + " but got nothing");
        var t = take();
        if (!t.is(s)) throw new RuntimeException("Expected " + s + " but got " + t);
        return this;
    }

    /** Add a token at the end. Return this. */
    private Parser add(Tok t) {
        toks.addLast(t);
        return this;
    }

    /** Report an error at the position of the provided token. */
    void failAt(String msg, Tok last) {
        throw new RuntimeException(last == null ? "Huh?" : last.errorAt(msg));
    }

    /** Fail if there are leftover tokens. */
    void failIfEmpty(String msg, Tok last) {
        if (isEmpty()) failAt(msg, last);
    }

    /** Return contents of the parser as a string. */
    String foldToString(String sep) {
        var sb = new StringBuilder();
        while (!isEmpty()) { // Recall that there is an EOF token at the end of the list
            sb.append(take().toString()); // Can't quite use streams.
            sb.append(sep);
        }
        return sb.toString();
    }

    /** Print debug info. */
    @Override
    public String toString() {
        var head = isEmpty() ? "EOF" : toks.getFirst().toString();
        var rest = "";
        for (int i = 1; i < toks.size() && i < 5; i++)
            rest += toks.get(i).toString();
        return head + "<-(" + toks.size() + ")->" + rest;
    }

    /** Return the current line of source input */
    String getLine() {
        return peek().getLine();
    }

    /**
     * Used for debugging, put the name of a file that contains the output of
     * code_warntype, and run the parser on it. Our current parser is best effort in
     * that we don't complelly care about accuracy in the body of the mthod. When
     * there is a missparse we just turn the line into a string.
     */
    public static void main(String[] args) {
        // GenDB.readDB();
        var p = new Parser();
        var file = "/tmp/jl_112553/out.0/t8465.tst";
        MethodInformation.parse(p.withFile(file).lex(), file.toString());
    }
}

/** An early form of Julia Type coming from the Parser. */
interface Ty {

    final static Ty Any = new TyInst(GenDB.it.names.any(), List.of()); // cache the singleton
    final static Ty None = new TyUnion(List.of()); // cache the singleton

    /** @return the Any Ty */
    static Ty any() {
        return Any;
    }

    /** @return the None Ty, ie. Union{} */
    static Ty none() {
        return None;
    }

    /**
     * Transforms a Ty to a Type object, bounds are resolved from their enclosing
     * environment.
     *
     * @param env
     *                bounds in scope
     */
    Type toType(List<Bound> env);

    /**
     * The parser does not know what identifiers represent variables and what
     * represent types. The fix up method constructs a list of variables in scope
     * and replaces TyInst by TyVar when appropriate. Fixup also takes care of
     * `typed` which is turned into a constant (TyCon). If fixup encounters an
     * identifier that is not a varaiable and that does not occur in the GendDB, it
     * will assume that this is a missing type and add it.
     *
     * @param bounds
     *                   bounds in scope
     * @return a fixed up Ty
     */
    Ty fixUp(List<TyVar> bounds);

}

record TyInst(TypeName nm, List<Ty> tys) implements Ty, Serializable {

    @Override
    public String toString() {
        var args = tys.stream().map(Ty::toString).collect(Collectors.joining(","));
        return nm + (tys.isEmpty() ? "" : "{" + args + "}");
    }

    /** After this call we have a valid TyInst or TyVar, or a TyCon or None. */
    @Override
    public Ty fixUp(List<TyVar> bounds) {
        var varOrNull = bounds.stream().filter(v -> v.nm().equals(nm().toString())).findFirst();
        if (varOrNull.isPresent()) {
            if (tys.isEmpty()) return varOrNull.get();
            throw new RuntimeException("Type " + nm() + " is a variable used as a type.");
        }
        var args = new ArrayList<Ty>();
        var vars = new ArrayList<TyVar>();
        var newBounds = new ArrayList<TyVar>(bounds);
        for (var a : tys) {
            if (a instanceof TyVar tv && !inList(tv, bounds)) {
                tv = (TyVar) tv.fixUp(bounds);
                newBounds.add(tv);
                vars.add(tv);
                args.add(tv);
            } else {
                args.add(a.fixUp(newBounds));
            }
        }
        Ty t = new TyInst(nm, args);
        for (var v : vars)
            t = new TyExist(v, t);
        return t;
    }

    /** @return true if the given var is in the list of bounds. */
    private static boolean inList(TyVar tv, List<TyVar> bounds) {
        return bounds.stream().anyMatch(b -> b.nm().equals(tv.nm()));
    }

    /** TyInst becomes an Inst with all parameters recurisvely converted. */
    @Override
    public Type toType(List<Bound> env) {
        return new Inst(nm, tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
    }

}

record TyVar(String nm, Ty low, Ty up) implements Ty, Serializable {

    @Override
    public String toString() {
        return (!low.equals(Ty.none()) ? low + "<:" : "") + CodeColors.variable(nm) + (!up.equals(Ty.any()) ? "<:" + up : "");
    }

    /**
     * A Var has a reference to its defining Bound, in the process of converting a
     * Ty to a Type we need to have the bounds in scope so we can initialize the Var
     * with the correct bounds.
     */
    @Override
    public Type toType(List<Bound> env) {
        var vne_stream = env.reversed().stream();
        var maybe = vne_stream.filter(b -> b.nm().equals(nm)).findFirst();
        if (maybe.isPresent()) {
            return new Var(maybe.get());
        }
        throw new RuntimeException("Variable " + nm + " not found in environment");
    }

    /** Recursively fix up the bounds. */
    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return new TyVar(nm, low.fixUp(bounds), up.fixUp(bounds));
    }

}

/** A constant represented as a string. */
record TyCon(String nm) implements Ty, Serializable {

    @Override
    public String toString() {
        return nm;
    }

    @Override
    public Type toType(List<Bound> env) {
        return new Con(nm);
    }

    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return this;
    }
}

record TyTuple(List<Ty> tys) implements Ty, Serializable {

    @Override
    public String toString() {
        return "(" + tys.stream().map(Ty::toString).collect(Collectors.joining(",")) + ")";
    }

    @Override
    public Type toType(List<Bound> env) {
        return new Tuple(tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
    }

    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return new TyTuple(tys.stream().map(t -> t.fixUp(bounds)).collect(Collectors.toList()));
    }

}

record TyUnion(List<Ty> tys) implements Ty, Serializable {

    @Override
    public String toString() {
        return "[" + tys.stream().map(Ty::toString).collect(Collectors.joining("|")) + "]";
    }

    @Override
    public Type toType(List<Bound> env) {
        return new Union(tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
    }

    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return new TyUnion(tys.stream().map(t -> t.fixUp(bounds)).collect(Collectors.toList()));
    }

}

record TyExist(Ty v, Ty ty) implements Ty, Serializable {

    @Override
    public String toString() {
        return CodeColors.exists("∃") + v + CodeColors.exists(".") + ty;
    }

    /**
     * The TyExist becomes an Exist with all parameters recurisvely converted. Fix
     * up needs to patch the parser's work which may have created a TyInst for the
     * bound variable.
     */
    @Override
    public Ty fixUp(List<TyVar> bound) {
        var maybeVar = v();
        var body = ty();
        if (maybeVar instanceof TyVar defVar) {
            var tv = new TyVar(defVar.nm(), defVar.low().fixUp(bound), defVar.up().fixUp(bound));
            var newBound = new ArrayList<>(bound);
            newBound.add(tv);
            return new TyExist(tv, body.fixUp(newBound));
        } else if (maybeVar instanceof TyInst inst) {
            var tv = new TyVar(inst.nm().toString(), Ty.none(), Ty.any());
            var newBound = new ArrayList<>(bound);
            newBound.add(tv);
            return new TyExist(tv, body.fixUp(newBound));
        } else if (maybeVar instanceof TyTuple) {
            throw new RuntimeException("Should be a TyVar or a TyInst with no arguments: got tuple ");
        } else {
            throw new RuntimeException("Should be a TyVar or a TyInst with no arguments: " + maybeVar);
        }
    }

    @Override
    public Type toType(List<Bound> env) {
        String name;
        Type low;
        Type up;
        if (v() instanceof TyVar tvar) {
            name = tvar.nm();
            low = tvar.low().toType(env);
            up = tvar.up().toType(env);
        } else { // Given that we have alreadty performed fixup, this should not occur. Or?
            var inst = (TyInst) v();
            if (!inst.tys().isEmpty()) throw new RuntimeException("Should be a TyVar but is a type: " + ty);
            name = inst.nm().toString();
            low = GenDB.it.none;
            up = GenDB.it.any;
        }
        var b = new Bound(name, low, up);
        var newenv = new ArrayList<>(env);
        newenv.add(b);
        return new Exist(b, ty().toType(newenv));
    }

}

/**
 * A type declaration has modifiers, a name, type expression, a parent type, and
 * source text.
 */
record TyDecl(String mod, TypeName nm, Ty ty, Ty parent, String src) implements Serializable {

    @Override
    public String toString() {
        return nm + " = " + mod + " " + ty + " <: " + parent + CodeColors.comment("\n# " + src);
    }

    /**
     * Given a type declaration,
     *
     * <pre>
     *   mod lhs <: rhs end
     * </pre>
     *
     * where lhs has the form T{X} and T is a type name, X is a variable, and rhs
     * has the form S{Ty} where S is a type name and Ty is a Ty expression. This
     * method fixes up X and rhs. It also wraps LHS into TyExists.
     */
    public TyDecl(String mod, TypeName nm, Ty ty, Ty parent, String src) {

        var lhs = (TyInst) ty;
        var rhs = (TyInst) parent;
        var fixedArgs = new ArrayList<TyVar>();
        for (var targ : lhs.tys()) {
            switch (targ) {
            case TyVar tv -> fixedArgs.add((TyVar) tv.fixUp(fixedArgs));
            case TyInst ti -> {
                if (!ti.tys().isEmpty()) throw new RuntimeException("Should be TyVar but is: " + targ);
                fixedArgs.add(new TyVar(ti.nm().toString(), Ty.none(), Ty.any()));
            }
            default -> throw new RuntimeException("Should be TyVar or TyInst with no arguments: " + targ);
            }
        }
        var fixedRHS = (TyInst) rhs.fixUp(fixedArgs);
        var args = new ArrayList<Ty>();
        args.addAll(fixedArgs);
        Ty t = new TyInst(lhs.nm(), args);
        for (var arg : fixedArgs.reversed())
            t = new TyExist(arg, t);

        this.ty = t;
        this.parent = fixedRHS;
        this.src = src;
        this.mod = mod;
        this.nm = nm;
    }
}

/**
 * The representation of a method with a name and type signature (a tuple
 * possibly wrapped in existentials). The source information is retained for
 * debugging purposes.`argnms` hold the argument names.`kwpos` holds the
 * position of the first keywork argument, or -1 if none.
 */
record TySig(FuncName nm, Ty ty, List<String> argnms, int kwPos, String src) implements Serializable {

    @Override
    public String toString() {
        return "function " + nm + " " + ty + CodeColors.comment("\n# " + src);
    }

    TySig fixUp() {
        return new TySig(nm, ty.fixUp(new ArrayList<>()), argnms, kwPos, src);
    }
}
