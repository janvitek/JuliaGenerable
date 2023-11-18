import java.util.ArrayList;
import java.util.List;

class Type {
    static Type parse(Parser p) {
        return BoundVar.parse(p);
    }
}

class DependentType extends Type {
    String value;
    DependentType(String value) {
        this.value = value;
    }
    static Type parse(Parser p) {
        var tok = p.peek();
        if (tok.isNumber()){
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
        if (tok.delim("{")) {
            tok = p.advance().peek();
            while (!tok.delim("}")) {
                if (tok.delim(",")) {
                    tok = p.advance().peek();
                    continue;
                } else {
                    typeParams.add(Type.parse(p));
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
                return new BoundVar(t.toString(), null, null);
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
        if (tok.delim("where")) {
            p.advance();
            var boundVars = new ArrayList<Type>();
            boundVars.add(BoundVar.parse(p));
            while (p.peek().delim(","))
                boundVars.add(BoundVar.parse(p.advance()));
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
                p.fail("Invalid type declaration");
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
            p.fail("Invalid type declaration");
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
                p.fail("Missed end of declaration");
            return new TypeDeclaration(modifiers, name, typeParams, parent);
        } else {
            p.fail("Invalid type declaration");
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

    static TypeName parse(Parser p) {
        var str = p.nextIdentifier().toString();
        var tok = p.peek();
        while (tok != null && tok.delim(".")) {
            str += tok + p.advance().nextIdentifier().toString();
            tok = p.peek();
        }            
        return new TypeName(str);
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