import java.util.ArrayList;
import java.util.List;

class Type {
}

class DependentType extends Type {
    String value;

    DependentType(String value) {
        this.value = value;
    }

    static Type parse(Parser p) {
        var tok = p.peek();
        if (tok.isNumber()) {
            p.advance();
            return new DependentType(tok.toString());
        }
        return null;
    }

}

// Int
// Int{X}
// Int{X,Y}
class TypeInst extends Type {
    TypeName name;
    List<Type> typeParams;

    TypeInst(TypeName name, List<Type> typeParams) {
        this.name = name;
        this.typeParams = typeParams;
    }

    static Type parse(Parser p) {
        var dep = DependentType.parse(p);
        if (dep != null)
            return dep;
        var name = TypeName.parse(p);
        var typeParams = new ArrayList<Type>();
        var tok = p.peek();
        if (tok.isString()) {
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
                    typeParams.add(UnionAllInst.parse(p));
                }
                tok = p.peek();
            }
            p.next();
        }
        return new TypeInst(name, typeParams);
    }

    @Override
    public String toString() {
        var str = name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "{";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1)
                    str += ", ";
            }
            str += "}";
        }
        return str;
    }
}

// X
// X <: Y
// X <: Y <: Z
// X >: Y
// <: Y
// >: Y
// X <: Int{Y} where Y
class BoundVar extends Type {
    TypeName name;
    Type lower;
    Type upper;

    BoundVar(String name, Type lower, Type upper) {
        this.name = new TypeName(name);
        this.lower = lower;
        this.upper = upper;
    }

    static Type parse(Parser p) {
        var tok = p.peek();
        if (tok.delim("<:")) {
            var upper = UnionAllInst.parse(p.advance());
            return new BoundVar("???", null, upper);
        } else {
            var t = UnionAllInst.parse(p);
            tok = p.peek();
            if (tok.delim("<:")) {
                var upper = UnionAllInst.parse(p.advance());
                return new BoundVar(t.toString(), null, upper);
            } else if (tok.delim(">:")) {
                var lower = UnionAllInst.parse(p.advance());
                return new BoundVar(t.toString(), lower, null);
            } else
                return t;
        }
    }

    @Override
    public String toString() {
        var str = name.toString();
        if (lower != null)
            str = lower.toString() + " <: " + str;
        if (upper != null)
            str = str + " <: " + upper.toString();
        return str;
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
            while (p.peek().delim(","))
                boundVars.add(BoundVar.parse(p.advance()));
            if (gotBrace) {
                if (p.peek().delim("}"))
                    p.advance();
                else
                    p.failAt("Missing closing brace", p.peek());
            }
            return new UnionAllInst(type, boundVars);
        } else
            return type;
    }

    @Override
    public String toString() {
        var str = type.toString();
        if (boundVars != null && !boundVars.isEmpty()) {
            str += " where ";
            for (int i = 0; i < boundVars.size(); i++) {
                str += boundVars.get(i).toString();
                if (i < boundVars.size() - 1)
                    str += ", ";
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

    TypeDeclaration(String modifiers, TypeName name, List<Type> typeParams, Type parent) {
        this.modifiers = modifiers;
        this.name = name;
        this.typeParams = typeParams;
        this.parent = parent;
    }

    static String parseModifiers(Parser p) {
        var str = "";
        var tok = p.next();
        if (tok.ident("abstract")) {
            str += "abstract ";
            tok = p.next();
            if (tok.ident("type")) {
                str += "type ";
            } else
                p.failAt("Invalid type declaration", tok);
            return str;
        }
        if (tok.ident("mutable")) {
            str += "mutable ";
            tok = p.next();
        }
        if (tok.ident("struct")) {
            str += "struct ";
            return str;
        } else
            p.failAt("Invalid type declaration", tok);
        return null;
    }

    static TypeDeclaration parse(Parser p) {
        var modifiers = parseModifiers(p);
        var name = TypeName.parse(p);
        var typeParams = new ArrayList<Type>();
        var tok = p.peek();
        if (tok.delim("{")) {
            tok = p.advance().peek();
            while (!tok.delim("}")) {
                if (tok.delim(",")) {
                    tok = p.advance().peek();
                    continue;
                } else
                    typeParams.add(BoundVar.parse(p));
                tok = p.peek();
            }
            p.advance(); // skip '}'
        }
        tok = p.next();
        if (tok.ident("end"))
            return new TypeDeclaration(modifiers, name, typeParams, null);
        else if (tok.delim("<:")) {
            var parent = TypeInst.parse(p);
            tok = p.next();
            if (!tok.ident("end"))
                p.failAt("Missed end of declaration", tok);
            return new TypeDeclaration(modifiers, name, typeParams, parent);
        } else {
            p.failAt("Invalid type declaration", tok);
            return null; // not reached
        }
    }

    @Override
    public String toString() {
        var str = modifiers + name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "{";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1)
                    str += ", ";
            }
            str += "}";
        }
        if (parent != null)
            str += " <: " + parent.toString();
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
        return str;
    }

    static TypeName parse(Parser p) {
        var tok = p.peek();
        if (tok.ident("typeof") || tok.ident("keytype")) {
            var str = tok.toString();
            tok = p.advance().next();
            str += tok.toString();
            if (!tok.delim("("))
                p.failAt("Missed opening brace for typeof", tok);
            tok = p.next();
            while (!tok.delim(")")) {
                str += tok.toString();
                tok = p.next();
            }
            str += ")";
            return new TypeName(str);
        } else
            return new TypeName(TypeName.readDotted(p));
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
        var tok = p.peek();
        if (tok.delim("::")) {
            tok = p.advance().peek();
            return UnionAllInst.parse(p);
        }
        return null;
    }

    static String parseValue(Parser p) {
        if (!p.peek().delim("="))
            return null;
        return Expression.parse(p.advance()).toString();
    }

    static String parseVarargs(Parser p) {
        var tok = p.peek();
        if (tok.delim("...")) {
            p.advance();
            return "...";
        }
        return null;
    }

    static Param parse(Parser p) {
        var tok = p.peek();
        var gotParen = false;
        if (tok.delim("@")) {
            p.advance();
            tok = p.peek();
            if (tok.delim("(")) {
                tok = p.advance().peek();
                gotParen = true;

            }

        }

        var name = "???";
        Type type = null;
        if (tok.isIdentifier()) {
            name = tok.toString();
            tok = p.advance().peek();
        } else if (tok.delim("(")) {
            name = "(";
            tok = p.advance().peek();
            while (!tok.delim(")")) {
                if (tok.delim(",") || tok.delim(";")) {
                    tok = p.advance().peek();
                    continue;
                } else
                    name += tok.toString();
                tok = p.advance().peek();
            }
            name += ")";
            tok = p.advance().peek();
        }
        type = Param.parseType(p);
        var value = Param.parseValue(p);
        var varargs = Param.parseVarargs(p);
        if (gotParen) {
            if (tok.delim(")")) {
                p.advance();
            } else
                p.failAt("Missing closing paren", tok);
        }
        if (name.equals("???") && type == null && value == null && varargs == null)
            p.failAt("Invalid parameter", p.peek());
        return new Param(name, type, value, varargs);
    }

    @Override
    public String toString() {
        var str = name;
        if (type != null)
            str += " :: " + type.toString();
        if (value != null)
            str += " = " + value;
        if (varargs != null)
            str += "...";
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
        } else
            p.failAt("Missied function keyword", tok);
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
            if (tok.delim(","))
                tok = p.advance().peek();
            else
                break;
        }
        if (gotBrace) {
            if (tok.delim("}")) {
                p.advance();
            } else
                p.failAt("Missing closing brace", tok);
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
                } else
                    f.typeParams.add(Param.parse(p));
                tok = p.peek();
            }
            p.advance(); // skip '}'
        }
        if (p.peek().delim("::")) {
            p.advance();
            TypeInst.parse(p); // ignore the return type

        }
        if (p.peek() != null && p.peek().ident("where")) {
            p.advance();
            parseWhere(p, f.wheres);
        }
        if (p.peek() != null && p.peek().ident("where")) {
            p.advance();
            parseWhere(p, f.wheres);
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
                if (i < typeParams.size() - 1)
                    str += ", ";
            }
            str += ")";
        }
        if (wheres != null && !wheres.isEmpty()) {
            str += " where ";
            for (int i = 0; i < wheres.size(); i++) {
                str += wheres.get(i).toString();
                if (i < wheres.size() - 1)
                    str += ", ";
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
            if (tok.delim("("))
                level++;
            else if (tok.delim(")"))
                level--;
            tok = p.advance().peek();
        }
        return new Expression(str);
    }

    @Override
    public String toString() {
        return value;
    }
}