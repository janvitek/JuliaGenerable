module TypeDiscover

export typediscover

struct OpaqueDiscovery
    x::Union{Function,Tuple{Module,Symbol,Type}}
end

abstract type Discovery end
abstract type FunctionDiscovery end
abstract type TypeDiscovery end

struct DiscoveredMacro <: FunctionDiscovery
    f::Function
    m::Method
end
struct DiscoveredLambda <: FunctionDiscovery
    f::Function
    m::Method
end
struct DiscoveredConstructor <: FunctionDiscovery
    f::Function
    m::Method
end
struct DiscoveredCallable <: FunctionDiscovery
    f::Function
    m::Method
end
struct DiscoveredBuiltin <: FunctionDiscovery
    f::Function
    m::Method
end
struct DiscoveredIntrinsic <: FunctionDiscovery
    f::Function
    m::Method
end
struct DiscoveredGeneric <: FunctionDiscovery
    f::Function
    m::Method
end

struct DiscoveredAlias <: TypeDiscovery
    m::Module
    s::Symbol
    t::Type
end
struct DiscoveredClosure <: TypeDiscovery
    m::Module
    s::Symbol
    t::Type
end
struct DiscoveredFunctionType <: TypeDiscovery
    m::Module
    s::Symbol
    t::Type
end
struct DiscoveredType <: TypeDiscovery
    m::Module
    s::Symbol
    t::Type
end

makefuncdiscovery(f::Function, m::Method) = begin
    f isa Core.Builtin && return DiscoveredBuiltin(f, m)
    f isa Core.IntrinsicFunction && return DiscoveredIntrinsic(f, m)
    mt = typeof(f).name.mt
    name = mt.name
    hasname = isdefined(mt.module, name) && typeof(getfield(mt.module, name)) <: Function
    sname = string(name)
    if hasname
        if startswith(sname, '@')
            return DiscoveredMacro(f, m)
        else
            return DiscoveredGeneric(f, m)
        end
    elseif '#' in sname
        return DiscoveredLambda(f, m)
    elseif mt === Base._TYPE_NAME.mt
        return DiscoveredConstructor(f, m)
    else
        return DiscoveredCallable(f, m)
    end
end

maketypediscovery(m::Module, s::Symbol, t::Type) = begin
    base = Base.unwrap_unionall(t)
    if t <: Function
        if occursin("var\"#", string(base))
            return DiscoveredClosure(m, s, t)
        else
            return DiscoveredFunctionType(m, s, t)
        end
    elseif base isa Union || t !== base.name.wrapper || string(s) != string(nameof(t))
        return DiscoveredAlias(m, s, t)
    else
        return DiscoveredType(m, s, t)
    end
end

ismodulenested(m::Module, outer::Module)::Bool = begin
    sentinel = Base.moduleroot(m)
    while true
        m === outer && return true
        m === sentinel && return false
        m = parentmodule(m)
    end
end

discover(report::Function, modules::Vector{Module}) = begin
    visited = Set{Module}()
    discovered = Set{OpaqueDiscovery}([
        OpaqueDiscovery((Core, :Union, Union)),
        OpaqueDiscovery((Core, :UnionAll, UnionAll)),
        OpaqueDiscovery((Core, :DataType, DataType)),
        OpaqueDiscovery((Core, :TypeofBottom, Core.TypeofBottom))
    ])

    discoveraux(mod::Module, root::Module) = begin
        mod ∈ visited && return
        push!(visited, mod)

        for sym in names(mod; all=true, imported=true)
            try
                val = getproperty(mod, sym)

                if val isa Module && ismodulenested(val, root)
                    discoveraux(val, root)
                    continue
                end

                if val isa Function && sym ∉ [:include, :eval]
                    d = OpaqueDiscovery(val)
                    d ∈ discovered && continue
                    push!(discovered, d)
                    for m in methods(val)
                        any(mod -> ismodulenested(m.module, mod), modules) && report(makefuncdiscovery(val, m))
                    end
                end

                if val isa Type
                    d = OpaqueDiscovery((mod, sym, val))
                    d ∈ discovered && continue
                    push!(discovered, d)
                    report(maketypediscovery(mod, sym, val))
                end

            catch e
                if e isa UndefVarError
                    GlobalRef(mod, sym) ∉ [GlobalRef(Base, :active_repl), GlobalRef(Base, :active_repl_backend),
                        GlobalRef(Base.Filesystem, :JL_O_TEMPORARY), GlobalRef(Base.Filesystem, :JL_O_SHORT_LIVED),
                        GlobalRef(Base.Filesystem, :JL_O_SEQUENTIAL), GlobalRef(Base.Filesystem, :JL_O_RANDOM)] &&
                        @warn "Module $mod exports symbol $sym but it's undefined."
                else
                    throw(e)
                end
            end
        end
    end

    for m in modules
        discoveraux(m, m)
    end

end

module TestMod

# import SentinelArrays
# const SVec{T} = SentinelArrays.SentinelVector{T, T, Missing, Vector{T}}

# gener(captured) = [captured + i for i in 1:2]

# f(abc) = x -> abc + x

# const lambda = (x::Int32) -> x + 1

# const MyVector{T} = Array{T,1}
# const MyVector2 = Vector
# const MyVector3 = Vector{T} where T
# const MyVectorInt = Vector{Int}
# struct CrazyArray{A, B, C, D, E} end
# const MyCrazyArray{T, U} = CrazyArray{T, U, Int, Bool, 3}

# const my8by16 = NTuple{16, VecElement{UInt8}}
# const my8by3 = NTuple{3, VecElement{UInt8}}

# struct X{T}
#     x::T
# end
# function (x::X)(a) # X.body.name.mt has callable objects (one mt for all of them??)
#     return x.x + a
# end

struct K
    k::Int
end
K(a, b, c) = K(a + b + c)

# abstract type TestAbstract end

# struct TestStruct <: TestAbstract end

# kwargs(x::Int; kw1::String = "hi", kw2::Bool) = 1

# vargs(x::String, y::Int...) = 1

# defaultargs(x::Int, def1::String = "hey", def2::Bool = false) = 1

# testfunc(x, y::String) = 1

# import Base.Int8

# primitive type TestPrimitive 40 end

# abstract type Abs{T} end
# struct Conc{T, U <: Array{<:T}} <: Abs{T} end

# foo(::Vector{T}, ::T) where T <: Number = 1

# module Submod
# const MyVector2 = Vector

# struct Substruct
#     x::Int
# end

# subfunction(x::Int, y::Bool)::String = "$x, $y"
# import ..testfunc
# testfunc(::Bool) = 1

# end

end

baseShowMethodCustom(io::IO, m::Method, kind::String) = begin
    tv, decls, file, line = Base.arg_decl_parts(m)
    sig = Base.unwrap_unionall(m.sig)
    if sig === Tuple
        # Builtin
        print(io, m.module, ".", m.name, "(...)  [", kind, "]")
        return
    end
    print(io, m.module, ".", decls[1][2], "(")
    join(
        io,
        String[isempty(d[2]) ? d[1] : string(d[1], "::", d[2]) for d in decls[2:end]],
        ", ",
        ", ",
    )
    kwargs = Base.kwarg_decl(m)
    if !isempty(kwargs)
        print(io, "; ")
        join(io, map(Base.sym_to_string, kwargs), ", ", ", ")
    end
    print(io, ")")
    Base.show_method_params(io, tv)

    print(io, "  [", kind)
    if line > 0
        file, line = Base.updated_methodloc(m)
        print(io, " @ ", file, ":", line)
    end
    print(io, "]")
end

baseShowTypeCustom(io::IO, @nospecialize(x::Type)) = begin
    if !Base.print_without_params(x)
        properx = Base.makeproper(io, x)
        if Base.make_typealias(properx) !== nothing || (Base.unwrap_unionall(x) isa Union && x <: Base.make_typealiases(properx)[2])
            # show(IOContext(io, :compact => true), x)
            if !(get(io, :compact, false)::Bool)
                Base.printstyled(IOContext(io, :compact => false), x)
            end
            return
        end
    end
    show(io, x)
end

Base.show(io::IO, d::DiscoveredMacro) = begin
    print(io, "function ")
    baseShowMethodCustom(io, d.m, "macro")
end

Base.show(io::IO, d::DiscoveredLambda) = begin
    print(io, "function ")
    baseShowMethodCustom(io, d.m, "lambda")
end

Base.show(io::IO, d::DiscoveredConstructor) = begin
    print(io, "function ")
    baseShowMethodCustom(io, d.m, "constructor")
end

Base.show(io::IO, d::DiscoveredCallable) = begin
    print(io, "function ")
    baseShowMethodCustom(io, d.m, "callable")
end

Base.show(io::IO, d::DiscoveredBuiltin) = begin
    print(io, "function ")
    baseShowMethodCustom(io, d.m, "builtin")
end

Base.show(io::IO, d::DiscoveredIntrinsic) = begin
    print(io, "function ")
    baseShowMethodCustom(io, d.m, "intrinsic")
end

Base.show(io::IO, d::DiscoveredGeneric) = begin
    print(io, "function ")
    baseShowMethodCustom(io, d.m, "generic")
end

Base.show(io::IO, d::DiscoveredAlias) = begin
    print(io, "const $(d.m).$(d.s) = ")
    baseShowTypeCustom(io, d.t)
end

Base.show(io::IO, d::DiscoveredClosure) = begin
    show(io, DiscoveredType(d.m, d.s, d.t))
end

Base.show(io::IO, d::DiscoveredFunctionType) = begin
    show(io, d.t)
end

Base.show(io::IO, d::DiscoveredType) = begin
    print(io,
        if isabstracttype(d.t)
            "abstract type"
        elseif isstructtype(d.t)
            ismutabletype(d.t) ? "mutable struct" : "struct"
        elseif isprimitivetype(d.t)
            "primitive type"
        else
            "???"
        end)
    print(io, " ", Base.unwrap_unionall(d.t))
    if supertype(d.t) !== Any
        print(io, " <: ")
        b = Base.unwrap_unionall(supertype(d.t))
        Base.show_type_name(io, b.name)
        isempty(b.parameters) || print(io, "{")
        print(io, join(map(p -> p isa TypeVar ? p.name : p, b.parameters), ", "))
        isempty(b.parameters) || print(io, "}")
    end
    if isprimitivetype(d.t)
        print(io, " ", 8 * sizeof(d.t))
    end
    print(io, " end")
end

typediscover(mods::AbstractVector{Module}=Base.loaded_modules_array();
    funcfile=nothing,
    typefile=nothing,
    skip_macros=true,
    skip_lambdas=true,
    skip_constructors=false,
    skip_callable=false,
    skip_builtins=true,
    skip_intrinsics=true,
    skip_generics=false,
    skip_aliases=true,
    skip_closures=true,
    skip_functiontypes=true,
    skip_types=false) = begin

    mods = filter(m -> m !== TypeDiscover, mods)

    funio = isnothing(funcfile) ? Base.stdout : open(funcfile, "w")
    typio = isnothing(typefile) ? Base.stdout : open(typefile, "w")

    shouldShow(::DiscoveredMacro) = !skip_macros
    shouldShow(::DiscoveredLambda) = !skip_lambdas
    shouldShow(::DiscoveredConstructor) = !skip_constructors
    shouldShow(::DiscoveredCallable) = !skip_callable
    shouldShow(::DiscoveredBuiltin) = !skip_builtins
    shouldShow(::DiscoveredIntrinsic) = !skip_intrinsics
    shouldShow(::DiscoveredGeneric) = !skip_generics
    shouldShow(::DiscoveredAlias) = !skip_aliases
    shouldShow(::DiscoveredClosure) = !skip_closures
    shouldShow(::DiscoveredFunctionType) = !skip_functiontypes
    shouldShow(::DiscoveredType) = !skip_types

    try
        discover(mods) do x
            shouldShow(x) || return
            io = x isa FunctionDiscovery ? funio : typio
            println(io, x)
        end
    finally
        funio === Base.stdout || close(funio)
        typio === Base.stdout || close(typio)
    end
end

end # module TypeDiscover
