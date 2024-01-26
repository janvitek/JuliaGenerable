package prlprg;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import prlprg.App.Timer;
import static prlprg.CodeColors.color;
import prlprg.NameUtils.FuncName;
import prlprg.NameUtils.TypeName;

/**
 * Parser for Julia type and method signatures, and files from code_warntype.
 * 
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

        /**
         * Type names are messy, see the doc for details.
         */
        static TypeName parseTypeName(Parser p) {
            var nm = p.take().toString();
            if (p.has("(")) {
                var q = p.sliceMatchedDelims("(", ")");
                nm += "(";
                while (!q.isEmpty())
                    nm += q.take().toString();
                nm += ")";
            }
            return GenDB.it.names.type(nm);
        }

        /**
         * Function names are dotted identifiers and delims.
         */
        static FuncName parseFunctionName(Parser p) {
            var str = "";
            while (!p.isEmpty() && !p.peek().is("(") && !p.peek().is("::"))
                str += p.take().toString();
            if (str.isEmpty()) p.failAt("Missing function name", p.peek());
            return GenDB.it.names.function(str);
        }
    }

    /**
     * An instance of a datatype constructor (not a union all or bound var).
     */
    record TypeInst(TypeName nm, List<ParsedType> ps) implements ParsedType {

        static ParsedType parse(Parser p) {
            var name = ParsedType.parseTypeName(p);
            if (!p.has("{")) return new TypeInst(name, null); // null denotes absence of type params.
            List<ParsedType> params = new ArrayList<>();
            var q = p.sliceMatchedDelims("{", "}");
            while (!q.isEmpty())
                params.add(BoundVar.parse(q.sliceNextCommaOrSemi()));
            return new TypeInst(name, params);
        }

        /**
         * Turns a ParsedType to a Ty. This cleans up constants, tuples and unions. Note
         * the difference between:
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
            if (ps == null) return nm.likelyConstant() ? new TyCon(nm.toString()) : new TyInst(nm, new ArrayList<>());
            var tys = ps.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
            return nm.isTuple() ? new TyTuple(tys) : nm.isUnion() ? new TyUnion(tys) : new TyInst(nm, tys);
        }

        @Override
        public String toString() {
            return nm + (ps == null ? "" : "{" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + "}");
        }

        /**
         * When we see a TypeInst in a parent position we make the type is in the DB.
         */
        void addMissing() {
            if (!nm.likelyConstant()) GenDB.it.types.addMissing(nm);
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
                if (p.isEmpty()) return new TypeInst(GenDB.it.names.getShort("Tuple"), new ArrayList<>());
            }
            var type = TypeInst.parse(p);
            if (!p.has("where")) return type;
            if (p.drop().has("{")) p = p.sliceMatchedDelims("{", "}");
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
            var name = ParsedType.parseTypeName(p);
            name.seenDeclaration(); // update this type name to recall that it has a declaration
            var src = p.last.getLine();
            var ps = new ArrayList<ParsedType>();
            var q = p.sliceMatchedDelims("{", "}");
            while (!q.isEmpty())
                ps.add(BoundVar.parse(q.sliceNextCommaOrSemi()));

            TypeInst parent = p.has("<:") ? (TypeInst) TypeInst.parse(p.drop()) : new TypeInst(GenDB.it.names.getShort("Any"), new ArrayList<>());
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
            parent.addMissing();
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
            var nm = "";
            while (!p.isEmpty() && !p.has("::"))
                nm += p.take().toString();

            nm = nm.isEmpty() ? "?NA" : nm;
            var tok = p.take();
            var type = tok.is("::") ? UnionAllInst.parse(p) : null;
            var varargs = p.has("...") ? p.take().is("...") : false;
            if (type == null && p.has("::")) {
                type = UnionAllInst.parse(p.drop());
                varargs = p.has("...") ? p.take().is("...") : false;
            }
            return new Param(nm, type, varargs);
        }

        Ty toTy() {
            return ty == null ? Ty.any() : ty.toTy();
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
     */
    record Function(FuncName nm, List<Param> ps, List<ParsedType> wheres, String src) {

        static List<ParsedType> parseWhere(Parser p) {
            var wheres = new ArrayList<ParsedType>();
            if (!p.has("where")) return wheres;

            p.drop();
            if (p.has("{")) p = p.sliceMatchedDelims("{", "}"); // we only need to read the where clause

            while (!p.isEmpty()) {
                wheres.add(BoundVar.parse(p));
                if (p.has(",")) {
                    p.drop().failIfEmpty("Missing type parameter", p.peek());
                } else if (p.has("[")) // comment with line/file info
                    break;
            }
            return wheres;
        }

        static Function parse(Parser p) {
            NameUtils.reset();
            if (p.has("function")) { // keyword is optional
                p.drop();
            }
            var name = ParsedType.parseFunctionName(p);
            var source = p.last.getLine();
            var q = p.sliceMatchedDelims("(", ")");
            var params = new ArrayList<Param>();
            while (!q.isEmpty()) {
                var r = q.sliceNextCommaOrSemi();
                if (r.isEmpty()) break;

                params.add(Param.parse(r));
            }
            var wheres = parseWhere(p);
            if (p.has("[")) p.sliceMatchedDelims("[", "]"); // drops it

            return new Function(name, params, wheres, source);
        }

        TySig toTy() {
            List<Ty> tys = ps.stream().map(Param::toTy).collect(Collectors.toList());
            var reverse = wheres.reversed();
            Ty nty = new TyTuple(tys);
            for (var where : reverse)
                nty = new TyExist(where.toTy(), nty);

            return new TySig(nm, nty, src);
        }

        @Override
        public String toString() {
            return nm + "(" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")" + (wheres.isEmpty() ? "" : (" where " + wheres.stream().map(Object::toString).collect(Collectors.joining(", "))));
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
        List<Param> arguments;
        List<Param> locals;
        Type returnType;
        String src;
        List<Op> ops = new ArrayList<>();
        List<TySig> sigs = new ArrayList<>();

        @Override
        public String toString() {
            return decl.toString() + "\n" + (staticArguments.isEmpty() ? "" : "staticArguments: " + staticArguments + "\n") + (arguments.isEmpty() ? "" : "arguments: " + arguments + "\n")
                    + (locals.isEmpty() ? "" : "locals: " + locals + "\n") + "returnType: " + returnType + "\n" + "src: " + src + "\n" + ops.stream().map(Object::toString).collect(Collectors.joining("\n"));
        }

        Sig parseFun(Parser p) {
            var d = Function.parse(p).toTy();
            d = d.fixUp(new ArrayList<>());
            return new Sig(d.nm(), d.ty().toType(new ArrayList<>()), d.src());
        }

        void parseMethodInfoHeader(Parser p) {
            p.take("MethodInstance").take("for");
            var q = p.sliceLine();
            this.decl = parseFun(q);
            q = p.sliceLine().take("from");
            this.originDecl = parseFun(q);
            q.take("@");
            this.src = "";
            while (!q.isEmpty())
                src += q.take().toString() + " ";
            src = src.trim();
        }

        List<Type> parseStaticArguments(Parser p) {
            var bs = new ArrayList<Type>();
            if (p.has("Static")) {
                p.take("Static").take("Parameters");
                while (!p.isEmpty() && !p.has("Arguments") && !p.has("Locals") && !p.has("Body")) {
                    var q = p.sliceLine();
                    var pty = BoundVar.parse(q);
                    var ty = pty.toTy().fixUp(new ArrayList<>());
                    bs.add(ty.toType(new ArrayList<>()));
                }
            }
            return bs;
        }

        List<Param> parseArguments(Parser p) {
            var ps = new ArrayList<Param>();
            if (p.has("Arguments")) {
                p.take("Arguments");
                while (!p.isEmpty() && !p.has("Locals") && !p.has("Body")) {
                    var q = p.sliceLine();
                    ps.add(Param.parse(q));
                }
            }
            return ps;
        }

        List<Param> parseLocals(Parser p) {
            var ps = new ArrayList<Param>();
            if (p.has("Locals")) {
                p.take("Locals");
                while (!p.isEmpty() && !p.has("Body")) {
                    var q = p.sliceLine();
                    ps.add(Param.parse(q));
                }
            }
            return ps;
        }

        Type parseReturnType(Parser p) {
            if (!p.has("Body")) return null;

            p.take("Body").take("::");
            var pty = UnionAllInst.parse(p); // this type could be all caps because code_warntype...
            var ty = pty.toTy().fixUp(new ArrayList<>());
            return ty.toType(new ArrayList<>());
        }

        void parseHeader(Parser p) {
            parseMethodInfoHeader(p);
            this.staticArguments = parseStaticArguments(p);
            try {
                this.arguments = parseArguments(p);
            } catch (Exception e) {
                App.output("Error parsing arguments: " + e.getMessage());
            }
            try {
                this.locals = parseLocals(p);
            } catch (Exception e) {
                App.output("Error parsing locals: " + e.getMessage());
            }
            try {
                this.returnType = parseReturnType(p);
            } catch (Exception e) {
                App.output("Error parsing return type: " + e.getMessage());
            }
        }

        record Op(String tgt, FuncName op, List<String> args, ParsedType ret) {

            @Override
            public String toString() {
                if (tgt == null) return op + "(" + args.stream().collect(Collectors.joining(", ")) + ")" + (ret == null ? "" : " :: " + ret);
                if (tgt.equals("nop")) return "nop";
                return tgt + " = " + op + "(" + args.stream().collect(Collectors.joining(", ")) + ")" + (ret == null ? "" : " :: " + ret);
            }
        }

        void parseBody(Parser p) {
            while (!p.isEmpty()) {
                if (p.has("MethodInstance")) return; // there can be more than one MethodInstance per file
                // this happens when code_warntype is given arguments such as Any that may cover
                // multiple methods. Normally the type generator does not do that, but it can happen.
                var q = p.sliceLine();
                var last = q.peek();
                try {
                    if (q.peek().isNumber()) {
                        q.drop().drop();
                    } else if (q.peek().is("│")) {
                        q.drop();
                    } else if (q.peek().toString().startsWith("└")) {
                        q.drop();
                    } else {
                        throw new Error("Weird");
                    }
                    q.failIfEmpty("Expected more...", last);
                    var tok = q.peek();
                    if (tok.is("goto") || tok.is("return") || tok.is("nothing")) {
                        q.drop();
                        continue;
                    }
                    ops.add(parseOp(q));
                } catch (Throwable e) {
                    App.output("Error parsing op: " + last.getLine());
                }
            }
        }

        Op parseOp(Parser p) {
            if (p.has("(")) {
                var q = p.sliceMatchedDelims("(", ")");
                var nm = q.take().toString();
                q.take("=");
                var op = parseCall(q);
                return new Op(nm, op.op, op.args, op.ret);
            } else {
                var tok = p.peek();
                if (tok.toString().startsWith("%")) {
                    var tmp = p.take().toString();
                    p.take("=");
                    var op = parseCall(p);
                    return new Op(tmp, op.op, op.args, op.ret);
                } else {
                    var op = parseCall(p);
                    return new Op(null, op.op, op.args, op.ret);
                }
            }
        }

        Op inlineCall(Parser p) {
            var q = p.sliceMatchedDelims("(", ")");
            var lhs = q.take();
            var op = q.take();
            var rhs = q.take();
            var r = p.has("::") ? UnionAllInst.parse(p.take("::")) : null;
            return new Op(lhs.toString(), GenDB.it.names.function(op.toString()), List.of(lhs.toString(), rhs.toString()), r);
        }

        Op normalCall(Parser p) {
            var nm = ParsedType.parseFunctionName(p);
            var q = p.sliceMatchedDelims("(", ")");
            var args = new ArrayList<String>();
            while (!q.isEmpty()) {
                var r = q.sliceNextCommaOrSemi();
                args.add(r.take().toString());
            }
            var r = p.has("::") ? UnionAllInst.parse(p.take("::")) : null;
            return new Op(null, nm, args, r);
        }

        Op parseCall(Parser p) {
            return p.has("(") ? inlineCall(p) : normalCall(p);
        }

        static List<Method> parse(Parser p, String filename) {
            var ms = new ArrayList<Method>();
            while (!p.isEmpty()) {
                var mi = new MethodInformation();
                mi.parseHeader(p);
                mi.parseBody(p);
                var m = new Method(mi, filename);
                ms.add(m);
            }
            return ms;
        }
    }

    protected List<Lex.Tok> toks = new ArrayList<>(); // the current list of token, decreasing as we parse
    Lex.Tok last; // for debugging purposes, we try to keep the last token, so we can remember where an error occured

    /**
     * Initialiszes the parser with data held in a file.
     */
    Parser withFile(String path) {
        try {
            List<String> ls = Files.readAllLines(Path.of(path));
            toks = new Lex(ls.toArray(new String[0])).tokenize();
            stats.lines = ls.size();
            return this;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
            GenDB.it.addSig(Function.parse(sliceLine()).toTy());

        stats.sigs.stop();
        stats.sigsFound = GenDB.it.sigs.allSigs().size();
        stats.funsFound = GenDB.it.sigs.allNames().size();
        App.print("Processed " + stats.linesOfSigs + " lines of input, found " + stats.sigsFound + " sigs for " + stats.funsFound + " generic functions in " + stats.sigs);
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

    final Stats stats = new Stats();

    class Stats {
        int lines;
        int linesOfSigs;
        int sigsFound;
        int funsFound;
        int linesOfTypes;
        int typesFound;
        Timer sigs = new Timer();
        Timer types = new Timer();
    }

    enum Kind {
        DELIMITER, IDENTIFIER, STRING, EOF;
    }

    class Lex {

        String[] lns;
        int pos, off;
        static char[] delimiters = { '{', '}', ':', ',', ';', '=', '(', ')', '[', ']', '#', '<', '>' };

        Lex(String[] lns) {
            this.lns = lns;
        }

        List<Tok> tokenize() {
            List<Tok> toks = new ArrayList<>();
            while (true) {
                var tok = next();
                if (tok.k == Kind.EOF) break;
                toks.add(tok);
            }
            while (true) {
                var ntoks = combineTokens(new ArrayList<>(toks));
                if (toks.size() == ntoks.size()) return splitOffThreeDots(toks);
                toks = ntoks;
            }
        }

        List<Tok> splitOffThreeDots(List<Tok> toks) {
            var ntoks = new ArrayList<Tok>();
            for (var t : toks) {
                var s = t.toString();
                if (s.endsWith("...") && s.length() > 3) {
                    ntoks.add(new Tok(t.l, t.k, t.ln, t.start, t.end - 3));
                    ntoks.add(new Tok(t.l, Kind.DELIMITER, t.ln, t.end - 3, t.end));
                } else
                    ntoks.add(t);
            }
            return ntoks;
        }

        // Our parser is a bit dumb, we need to path the token stream, combining some adjacent
        // tokens to form the right one. This is a bit of a hack, but it works.
        List<Tok> combineTokens(List<Tok> toks) {
            var smashed = false;
            for (int i = 1; i < toks.size(); i++) {
                var prv = toks.get(i - 1);
                var cur = toks.get(i);
                var dd = prv.isDelimiter() && cur.isDelimiter();
                var is = prv.isIdentifier() && cur.isString();
                var di = prv.isDelimiter() && cur.isIdentifier();
                if (!dd && !is && !di) continue;

                if (matchSymbol(prv, cur, "::") || matchSymbol(prv, cur, ">:") || matchSymbol(prv, cur, "<<") || matchSymbol(prv, cur, "==") || matchSymbol(prv, cur, "===") || matchSymbol(prv, cur, "<:") || matchSymbol(prv, cur, ":>")) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.DELIMITER, prv.ln, prv.start, cur.end));
                    smashed = true;
                } else if (smashIdentString(prv, cur)) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.IDENTIFIER, prv.ln, prv.start, cur.end));
                    // This combines an identifier and a string that must be adjacent
                    smashed = true;
                } else if (smashColumnIdent(prv, cur)) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.IDENTIFIER, prv.ln, prv.start, cur.end));
                    // This combines an identifier and a string that must be adjenct
                    smashed = true;
                }
            }
            return smashed ? toks.stream().filter(t -> t != null).collect(Collectors.toList()) : toks;
        }

        boolean smashIdentString(Tok prv, Tok cur) {
            var adjacent = prv.end == cur.start;
            var combination = prv.isIdentifier() && cur.isString();
            return adjacent && combination;
        }

        boolean smashColumnIdent(Tok prv, Tok cur) {
            var adjacent = prv.end == cur.start;
            var prvIsCol = prv.length() == 1 && prv.charAt(prv.start) == ':';
            return adjacent && prvIsCol && prv.isDelimiter() && cur.isIdentifier();
        }

        boolean matchSymbol(Tok prv, Tok cur, String sym) {
            if (prv.end != cur.start) { // not adjacent, can't match is space separated
                return false;
            }
            if (cur.end - prv.start != sym.length()) { // not the right length
                return false;
            }
            for (int i = 0; i < sym.length(); i++)
                if (sym.charAt(i) != prv.charAt(i + prv.start)) { // can read past of token,
                    return false; // as long as both are on the same line, which they are
                }
            return true;
        }

        // We should probably cache the string value, but ... meh
        record Tok(Lex l, Kind k, int ln, int start, int end) {

            @Override
            public String toString() {
                return isEOF() ? "EOF" : getLine().substring(start, end);
            }

            String errorAt(String msg) {
                return "\n> " + getLine() + "\n> " + " ".repeat(start) + color("^----" + msg + " at line " + ln, "Red");
            }

            String getLine() {
                return l.lns[ln];
            }

            // can read past the end of the token, but not past the end of the line.
            char charAt(int i) {
                return getLine().charAt(i);
            }

            // Check that this token is the given string.
            boolean is(final String s) {
                if (length() != s.length()) { // have to be of the same length
                    return false;
                }
                for (int i = 0; i < s.length(); i++)
                    if (charAt(i + start) != s.charAt(i)) return false;
                return true;
            }

            int length() {
                return end - start;
            }

            // Check that this token is plausibly a number. It can have a single dot, and no sign/or E.
            boolean isNumber() {
                var dots = 0;
                for (int i = start; i < end; i++) {
                    var c = charAt(i);
                    var digit = Character.isDigit(c);
                    if (!digit && c != '.') return false;
                    if (!digit) dots++;
                }
                return dots == 0 || (dots == 1 && length() > 1);
            }

            boolean isEOF() {
                return k == Kind.EOF;
            }

            boolean isDelimiter() {
                return k == Kind.DELIMITER;
            }

            boolean isIdentifier() {
                return k == Kind.IDENTIFIER;
            }

            boolean isString() {
                return k == Kind.STRING;
            }
        }

        boolean isDelimiter(int c) {
            for (char d : delimiters) {
                if (c == d) {
                    return true;
                }
            }
            return false;
        }

        Tok next() {
            if (pos >= lns.length) return eof();

            if (off >= lns[pos].length()) {
                pos++;
                off = 0;
                return next();
            }
            int cp = lns[pos].codePointAt(off);
            var start = off;
            if (cp == 0x1B) {
                off++;
                while (off < lns[pos].length() && lns[pos].charAt(off) != 'm')
                    off++;
                off++;
                return next();
            } else if (Character.isWhitespace(cp)) {
                off++;
                while (off < lns[pos].length() && Character.isWhitespace(lns[pos].codePointAt(off)))
                    off++;
                return next();
            } else if (isDelimiter(cp)) {
                off++;
                return new Tok(this, Kind.DELIMITER, pos, start, off);
            } else if (cp == '"') {
                off++;
                while (off < lns[pos].length() && lns[pos].charAt(off) != '"')
                    off++;
                off++;
                return new Tok(this, Kind.STRING, pos, start, off);
            } else if (cp == '\'') {
                off++;
                while (off < lns[pos].length() && lns[pos].charAt(off) != '\'')
                    off++;
                off++;
                return new Tok(this, Kind.STRING, pos, start, off);
            } else {
                off++;
                while (off < lns[pos].length()) {
                    cp = lns[pos].codePointAt(off);
                    if (cp == 0x1B || Character.isWhitespace(cp) || isDelimiter(cp) || lns[pos].charAt(off) == '"' || lns[pos].charAt(off) == '\'') break;
                    off++;
                }
                return new Tok(this, Kind.IDENTIFIER, pos, start, off);
            }
        }
    }

    // Yields a new parser for the slice of tokens between s and its matching e.
    // Used for "(" and ")" and "{" and "}".
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

    Parser sliceLine() {
        var p = new Parser();
        if (isEmpty()) return p;
        int ln = peek().ln;
        while (!peek().isEOF() && peek().ln == ln)
            p.add(take());
        return p;
    }

    boolean isEmpty() {
        return toks.isEmpty();
    }

    boolean has(String s) {
        return peek().is(s);
    }

    Lex.Tok eof() {
        return new Lex.Tok(null, Kind.EOF, 0, 0, 0);
    }

    Lex.Tok peek() {
        var tok = !toks.isEmpty() ? toks.get(0) : eof();
        if (!tok.isEOF()) last = tok;
        return tok;
    }

    Parser drop() {
        toks.remove(0);
        return this;
    }

    Lex.Tok take() {
        return !toks.isEmpty() ? toks.remove(0) : eof();
    }

    Parser take(String s) {
        if (isEmpty()) throw new Error("Expected " + s + " but got nothing");
        var t = take();
        if (!t.is(s)) throw new Error("Expected " + s + " but got " + t);
        return this;
    }

    Parser add(Lex.Tok t) {
        toks.addLast(t);
        return this;
    }

    void failAt(String msg, Lex.Tok last) {
        throw new RuntimeException(last == null ? "Huh?" : last.errorAt(msg));
    }

    void failIfEmpty(String msg, Lex.Tok last) {
        if (isEmpty()) failAt(msg, last);
    }
}

/**
 * An early form of Julia Type coming from the Parser.
 *
 * @author jan
 */
interface Ty {

    final static Ty Any = new TyInst(GenDB.it.names.getShort("Any"), List.of());
    final static Ty None = new TyUnion(List.of());

    /**
     * @return the Any Ty
     */
    static Ty any() {
        return Any;
    }

    /**
     * @return the None Ty, ie. Union{}
     */
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
     * `typedf` which is turned into a constant (TyCon). If fixup encounters an
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

    /**
     * After this call we have a valid TyInst or TyVar, or a TyCon or a Ty.None.
     */
    @Override
    public Ty fixUp(List<TyVar> bounds) {
        var varOrNull = bounds.stream().filter(v -> v.nm().equals(nm().toString())).findFirst();
        if (varOrNull.isPresent()) {
            if (tys.isEmpty()) return varOrNull.get();
            throw new RuntimeException("Type " + nm() + " is a variable used as a type.");
        }
        if (nm.likelyConstant()) return new TyCon(nm.toString());
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

    /**
     * @return true if the given var is in the list of bounds.
     */
    private static boolean inList(TyVar tv, List<TyVar> bounds) {
        return bounds.stream().anyMatch(b -> b.nm().equals(tv.nm()));
    }

    /**
     * The TyInst becomes an Inst with all parameters recurisvely converted.
     */
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

    /**
     * Recursively fix up the bounds.
     */
    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return new TyVar(nm, low.fixUp(bounds), up.fixUp(bounds));
    }

}

/**
 * A constant such as 5 or a symbol or a typeof. The value of the constant is
 * kept as its original string representation.
 */
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
 * A type declaration has modifiers, a name, type expression and a parent type.
 * We also keep the source text for debugging purposes.
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
     *
     * @return a new TyDecl with all elements recurisvely fixed up
     */
    TyDecl fixUp() {
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
        return new TyDecl(mod, nm, t, fixedRHS, src);
    }

}

/**
 * The representation of a method with a name and type signature (a tuple
 * possibly wrapped in existentials). The source information is retained for
 * debugging purposes.
 */
record TySig(FuncName nm, Ty ty, String src) implements Serializable {

    @Override
    public String toString() {
        return "function " + nm + " " + ty + CodeColors.comment("\n# " + src);
    }

    /**
     * Fixes up the signatures of the method.
     */
    TySig fixUp(List<TyVar> bounds) {
        return new TySig(nm, ty.fixUp(bounds), src);
    }
}
