package prlprg;

import java.util.ArrayList;
import java.util.List;

abstract class Type {

    abstract Ty toTy();
}

// Numbers can appear in a type signature
class DependentType extends Type {

    String value;

    DependentType(String value) {
        this.value = value;
    }

    @Override
    Ty toTy() {
        return new TyCon(value);
    }

    static Type parse(Parser p) {
        var tok = p.peek();
        if (!tok.isNumber()) {
            return null;
        }
        p.advance();
        return new DependentType(tok.toString());
    }

}

// An instance of a datatype constructor (not a union all or bound)
class TypeInst extends Type {

    TypeName name;
    List<Type> typeParams;

    TypeInst(TypeName name, List<Type> typeParams) {
        this.name = name;
        this.typeParams = typeParams;
    }

    static Type parse(Parser p) {
        var dep = DependentType.parse(p);
        if (dep != null) // If we see a number, it's a dependent type
        {
            return dep;
        }
        var name = TypeName.parse(p);
        var typeParams = new ArrayList<Type>();
        var tok = p.peek();
        if (tok.isString()) { // Covers the case of MIME"xyz" which is a type
            name.name += tok.toString();
            p.advance();
        }
        if (tok.delim("{")) {
            tok = p.advance().peek();
            while (!tok.delim("}")) {
                if (tok.delim(",")) {
                    tok = p.advance().peek();
                    continue;
                } else {
                    typeParams.add(BoundVar.parse(p));
                }
                tok = p.peek();
            }
            p.next();
        }
        return new TypeInst(name, typeParams);
    }

    @Override
    Ty toTy() {
        var params = new ArrayList<Ty>();
        for (var param : typeParams) {
            params.add(param.toTy());
        }

        return name.name.equals("Tuple") ? new TyTuple(params)
                : name.name.equals("Union") ? new TyUnion(params)
                : new TyInst(name.name, params);
    }

    @Override
    public String toString() {
        var str = name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "{";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1) {
                    str += ", ";
                }
            }
            str += "}";
        }
        return str;
    }
}

class BoundVar extends Type {

    Type name;
    Type lower;
    Type upper;

    BoundVar(Type name, Type lower, Type upper) {
        this.name = name;
        this.lower = lower;
        this.upper = upper;
    }

    static Type huh = new TypeInst(new TypeName("???"), null);

    static Type parse(Parser p) {
        if (p.peek().delim("<:")) {
            return new BoundVar(huh, null, UnionAllInst.parse(p.advance()));
        } else {
            var type = UnionAllInst.parse(p);
            if (p.peek().delim("<:")) {
                var upper = UnionAllInst.parse(p.advance());
                Type lower = null;
                if (p.peek().delim("<:")) {
                    lower = UnionAllInst.parse(p.advance());
                }
                return new BoundVar(type, lower, upper);
            } else if (p.peek().delim(">:")) {
                return new BoundVar(type, UnionAllInst.parse(p.advance()), null);
            } else {
                return type;
            }
        }
    }

    @Override
    Ty toTy() {
        return new TyVar(name.toString(), lower == null ? Ty.none() : lower.toTy(),
                (upper == null || upper.toString().equals("Any")) ? Ty.any() : upper.toTy());
    }

    @Override
    public String toString() {
        return (lower != null ? lower + " <: " : "") + name.toString() + (upper != null ? " <: " + upper : "");
    }
}

// Int{X} where {Z <: X <: Y, Y <: Int} where {X <: Int}
class UnionAllInst extends Type {

    Type type;
    List<Type> boundVars;

    UnionAllInst(Type type, List<Type> boundVars) {
        this.type = type;
        this.boundVars = boundVars;
    }

    static Type parse(Parser p) {
        var type = TypeInst.parse(p);
        var tok = p.peek();
        if (tok.ident("where")) {
            p.advance();
            var boundVars = new ArrayList<Type>();
            var gotBrace = false;
            if (p.peek().delim("{")) {
                p.advance();
                gotBrace = true;
            }
            boundVars.add(BoundVar.parse(p));
            while (p.peek().delim(",")) {
                boundVars.add(BoundVar.parse(p.advance()));
            }
            if (gotBrace) {
                if (p.peek().delim("}")) {
                    p.advance();
                } else {
                    p.failAt("Missing closing brace", p.peek());
                }
            }
            return new UnionAllInst(type, boundVars);
        } else {
            return type;
        }
    }

    @Override
    Ty toTy() {
        var ty = type.toTy();
        var it = boundVars.listIterator(boundVars.size());
        while (it.hasPrevious()) {
            var boundVar = it.previous().toTy();
            ty = new TyExist(boundVar, ty);
        }
        return ty;
    }

    @Override
    public String toString() {
        var str = type.toString();
        if (boundVars != null && !boundVars.isEmpty()) {
            str += " where ";
            for (int i = 0; i < boundVars.size(); i++) {
                str += boundVars.get(i).toString();
                if (i < boundVars.size() - 1) {
                    str += ", ";
                }
            }
        }
        return str;
    }
}

class TypeDeclaration {

    String modifiers;
    TypeName name;
    List<Type> typeParams;
    Type parent;
    String sourceLine;

    TypeDeclaration(String modifiers, TypeName name, List<Type> typeParams, Type parent, String source) {
        this.modifiers = modifiers;
        this.name = name;
        this.typeParams = typeParams;
        this.parent = parent;
        this.sourceLine = source;

    }

    static String parseModifiers(Parser p) {
        var str = "";
        var tok = p.next();
        if (tok.ident("abstract") || tok.ident("primitive")) {
            str += tok.toString();
            tok = p.next();
            if (tok.ident("type")) {
                str += "type ";
            } else {
                p.failAt("Invalid type declaration", tok);
            }
            return str;
        }
        if (tok.ident("mutable")) {
            str += "mutable ";
            tok = p.next();
        }
        if (tok.ident("struct")) {
            str += "struct ";
            return str;
        } else {
            p.failAt("Invalid type declaration", tok);
        }
        return null;
    }

    static TypeDeclaration parse(Parser p) {
        var modifiers = parseModifiers(p);
        var name = TypeName.parse(p);
        var sourceLine = p.getLineAt(p.peek());
        var typeParams = new ArrayList<Type>();
        var tok = p.peek();
        if (tok.delim("{")) {
            tok = p.advance().peek();
            while (!tok.delim("}")) {
                if (tok.delim(",")) {
                    tok = p.advance().peek();
                    continue;
                } else {
                    typeParams.add(BoundVar.parse(p));
                }
                tok = p.peek();
            }
            p.advance(); // skip '}'
        }
        tok = p.next();
        if (tok.isNumber()) {
            tok = p.next();
        }
        if (tok.ident("end")) {
            return new TypeDeclaration(modifiers, name, typeParams, null, sourceLine);
        } else if (tok.delim("<:")) {
            var parent = TypeInst.parse(p);
            tok = p.next();
            if (tok.isNumber()) {
                tok = p.next();
            }
            if (!tok.ident("end")) {
                p.failAt("Missed end of declaration", tok);
            }
            return new TypeDeclaration(modifiers, name, typeParams, parent, sourceLine);
        } else {
            p.failAt("Invalid type declaration", tok);
            return null; // not reached
        }
    }

    TyDecl toTy() {
        var parentTy = (parent == null || parent.toString().equals("Any")) ? Ty.any() : parent.toTy();
        var boundVars = new ArrayList<Ty>();
        var args = new ArrayList<Ty>();
        for (Type t : typeParams) {
            var ty = t.toTy();
            boundVars.addLast(ty);
            args.add(ty);
        }
        Ty ty = new TyInst(name.toString(), args);
        var it = boundVars.listIterator(boundVars.size());
        while (it.hasPrevious()) {
            ty = new TyExist(it.previous(), ty);
        }
        return new TyDecl(name.toString(), ty, parentTy, sourceLine);
    }

    @Override
    public String toString() {
        var str = modifiers + name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "{";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1) {
                    str += ", ";
                }
            }
            str += "}";
        }
        if (parent != null) {
            str += " <: " + parent.toString();
        }
        return str;
    }
}

class TypeName {

    String name;

    TypeName(String name) {
        this.name = name;
    }

    static String readDotted(Parser p) {
        var tok = p.next();
        var str = tok.toString();
        tok = p.peek();
        while (tok.delim(".")) {
            str += "." + p.advance().nextIdentifier().toString();
            tok = p.peek();
        }
        if (p.peek().isString()) {
            str += p.next().toString();
        }
        return str;
    }

    static TypeName parse(Parser p) {
        var tok = p.peek();
        if (tok.ident("typeof") || tok.ident("keytype")) {
            var str = tok.toString();
            tok = p.advance().next();
            str += tok.toString();
            if (!tok.delim("(")) {
                p.failAt("Missed opening brace for typeof", tok);
            }
            tok = p.next();
            while (!tok.delim(")")) {
                str += tok.toString();
                tok = p.next();
            }
            str += ")";
            return new TypeName(str);
        } else {
            return new TypeName(TypeName.readDotted(p));
        }
    }

    @Override
    public String toString() {
        return name;
    }
}

class FunctionName {

    String name;

    FunctionName(String name) {
        this.name = name;
    }

    static FunctionName parse(Parser p) {
        var tok = p.next();
        var str = "";

        if (tok.delim("(")) {
            tok = p.next();
            while (!tok.delim(")")) {
                str += tok.toString();
                tok = p.next();
            }
        } else {
            str = tok.toString();
            tok = p.peek();
            while (!tok.delim("(")) {
                str += p.next();
                tok = p.peek();
            }
        }
        return new FunctionName(str);
    }

    @Override
    public String toString() {
        return name;
    }
}

class Param {

    String name;
    Type type;
    String value;
    String varargs;

    Param(String name, Type type, String value, String varargs) {
        this.name = name;
        this.type = type;
        this.value = value;
        this.varargs = varargs;
    }

    static Type parseType(Parser p) {
        return p.peek().delim("::") ? UnionAllInst.parse(p.advance()) : null;
    }

    static String parseValue(Parser p) {
        return p.peek().delim("=") ? Expression.parse(p.advance()).toString() : null;
    }

    static String parseVarargs(Parser p) {
        if (!p.peek().delim("...")) {
            return null;
        }
        p.advance();
        return "...";
    }

    static Param parse(Parser p) {
        var gotParen = false;
        if (p.peek().delim("@")) {
            p.advance().advance();
            if (p.peek().delim("(")) {
                p.advance();
                gotParen = true;
            }
        }

        var name = "???";
        Type type;
        if (p.peek().isIdentifier()) {
            name = p.next().toString();
        } else if (p.peek().delim("(")) {
            name = "(";
            p.advance();
            while (!p.peek().delim(")")) {
                name += p.next().toString();
            }
            name += ")";
            p.advance();
        }
        type = Param.parseType(p);
        var value = Param.parseValue(p);
        var varargs = Param.parseVarargs(p);
        if (gotParen && !p.next().delim(")")) {
            p.failAt("Missing closing paren", p.peek());
        }
        if (name.equals("???") && type == null && value == null && varargs == null) {
            p.failAt("Invalid parameter", p.peek());
        }
        return new Param(name, type, value, varargs);
    }

    @Override
    public String toString() {
        var str = name;
        if (type != null) {
            str += " :: " + type.toString();
        }
        if (value != null) {
            str += " = " + value;
        }
        if (varargs != null) {
            str += "...";
        }
        return str;
    }
}

class Function {

    String modifiers;
    FunctionName name;
    List<Param> typeParams = new ArrayList<>();
    List<Type> wheres = new ArrayList<>();

    void parseModifiers(Parser p) {
        var tok = p.peek();
        var str = "";
        if (tok.delim("@")) {
            str += "@";
            tok = p.peek();
            while (!tok.ident("function")) {
                str += p.next().toString();
                tok = p.peek();
            }
        }
        if (tok.ident("function")) {
            p.advance();
            str += "function ";
        } else {
            p.failAt("Missied function keyword", tok);
        }
        modifiers = str;
    }

    static List<Type> parseWhere(Parser p, List<Type> wheres) {
        var tok = p.peek();
        boolean gotBrace = false;
        if (tok.delim("{")) {
            gotBrace = true;
            tok = p.advance().peek();
        }
        while (true) {
            wheres.add(BoundVar.parse(p));
            tok = p.peek();
            if (tok.delim(",")) {
                tok = p.advance().peek();
            } else {
                break;
            }
        }
        if (gotBrace) {
            if (tok.delim("}")) {
                p.advance();
            } else {
                p.failAt("Missing closing brace", tok);
            }
        }
        return wheres;
    }

    static Function parse(Parser p) {
        var f = new Function();
        f.parseModifiers(p);
        f.name = FunctionName.parse(p);
        if (p.verbose) {
            System.out.println("- " + p.getLine());
        }
        var tok = p.peek();
        if (tok.delim("(")) {
            tok = p.advance().peek();
            while (!tok.delim(")")) {
                if (tok.delim(",") || tok.delim(";")) {
                    tok = p.advance().peek();
                    continue;
                } else {
                    f.typeParams.add(Param.parse(p));
                }
                tok = p.peek();
            }
            p.advance(); // skip '}'
        }
        if (p.peek().delim("::")) {
            p.advance();
            TypeInst.parse(p); // ignore the return type
        }
        if (p.peek().ident("where")) {
            p.advance();
            parseWhere(p, f.wheres);
        }
        if (p.peek().ident("where")) {
            p.advance();
            parseWhere(p, f.wheres);
        }
        if (p.peek().ident("@")) {
            while (!p.peek().isEOF() || !p.peek().ident("function")) {
                p.advance();
            }
        }
        if (p.verbose) {
            System.out.println("+ " + f);
        }
        return f;
    }

    @Override
    public String toString() {
        var str = modifiers + name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "(";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1) {
                    str += ", ";
                }
            }
            str += ")";
        }
        if (wheres != null && !wheres.isEmpty()) {
            str += " where ";
            for (int i = 0; i < wheres.size(); i++) {
                str += wheres.get(i).toString();
                if (i < wheres.size() - 1) {
                    str += ", ";
                }
            }
        }
        return str;
    }
}

class Expression {

    String value;

    Expression(String value) {
        this.value = value;
    }

    static boolean couldBeDone(Parser.Token tok, int level) {
        boolean possibleEnd = tok.delim(",") || tok.delim(";") || tok.delim(")");
        return possibleEnd && level <= 0;
    }

    static Expression parse(Parser p) {
        int level = 0; // number of parentheses that are open
        var str = "";
        var tok = p.peek();
        while (!couldBeDone(tok, level)) {
            str += tok.toString();
            if (tok.delim("(")) {
                level++;
            } else if (tok.delim(")")) {
                level--;
            }
            tok = p.advance().peek();
        }
        return new Expression(str);
    }

    @Override
    public String toString() {
        return value;
    }
}
