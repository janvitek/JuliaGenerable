package prlprg;

public class App {

    public static boolean debug, PRINT_HIERARCHY, NO_CLOSURES, SHORTEN;
    static GenDB db = new GenDB();
    static String dir, types, functions;
    static String[] defaultArgs = {
        "-d=FALSE", // run micro tests
        "-c=DARK", // color the output
        "-r=../Inputs/", // root directory with input files
        "-f=func.jlg", // file with function signatures
        "-t=type.jlg", // file with type declarations
        "-h=TRUE", // print hierarchy
        "-i=FALSE", // ignore closures
        "-s=FALSE", // print shorter type names
    };

    public static void main(String[] args) {
        parseArgs(defaultArgs); // set default values
        parseArgs(args); // override them with command line arguments
        var p = new Parser();
        p = debug ? p.withString(tstr) : p.withFile(dir + types);
        while (!p.isEmpty()) {
            db.addTyDecl(TypeDeclaration.parse(p.sliceLine()).toTy());
        }
        p = new Parser();
        p = debug ? p.withString(str) : p.withFile(dir + functions);
        while (!p.isEmpty()) {
            db.addSig(Function.parse(p.sliceLine()).toTy());
        }
        db.cleanUp();
        new Generator(db).gen();
    }

    static String tstr = """
      abstract type Tuple end
      abstract type B{X} end
      struct C{T<:Tuple{}, A<:B{Tuple{} } } <: B{T} end
      struct A{T<:B{<:B}} <: B{T} end
      abstract type Val{X} end
      abstract type R <: B{Val{:el}} end
      struct D{T, S, E} <: B{E} end
      struct Int32 end
      struct T1 <: Val{:el} end
      struct T2 <: Val{10} end
      struct T3 <: Val{\"hi\"} end
    """;
    static String str = """
    function ceil(::T) where T>:Missing
    a(::B{>:Missing}, ::Missing)
    !=(T::Type, S::Type)
    !==(x, y)
    &(::Integer, ::Missing)
    (::Colon)(I::CartesianIndex{N}, S::CartesianIndex{N}, J::CartesianIndex{N}) where N
    (::Tar.var\"#1#2\")(::Any)
    (f::Base.RedirectStdStream)(thunk::Function, stream)
    *(a, b, c, xs...)
    *(a::T, b::Union{AbstractChar, AbstractString, T}...) where T<:AbstractPath
    *(α::Number, β::Number, C::AbstractMatrix, D::AbstractMatrix)
    +(A::LinearAlgebra.UnitUpperTriangular, B::LinearAlgebra.UnitUpperTriangular)
    /(::Missing, ::Number)
    <(a::DataStructures.SparseIntSet, b::DataStructures.SparseIntSet)
    ===(...)
    >(a::Integer, b::SentinelArrays.ChainedVectorIndex)
    >=(x)
    >>>(x::Integer, c::Int64)
    LogBINV(::Val{:ℯ}, ::Type{Float32})
    LogBL(::Val{10}, ::Type{Float32})
    \\(D::LinearAlgebra.Diagonal, T::LinearAlgebra.Tridiagonal)
    ^(::Irrational{:ℯ}, A::AbstractMatrix)
    _markdown_parse_cell(io::IOContext, cell::Nothing; kwargs...)
    _maybe_reindex(V, I, ::Tuple{})
    _newsentinel!(::SentinelArray{T, N, S, V, A} where A<:AbstractArray{T, N}...; newsent, force) where {T, N, S, V}
    axpby!(α, x::AbstractArray, β, y::AbstractArray)
    broadcast(::typeof(*), x::CompressedVector{Tx}, y::CompressedVector{Ty}) where {Tx, Ty}
    broadcast(::typeof(-), x::SparseArrays.AbstractSparseVector{Tx}, y::SparseArrays.AbstractSparseVector{Ty}) where {Tx, Ty}
    broadcasted(::Base.Broadcast.DefaultArrayStyle{1}, ::typeof(/), r::AbstractRange, x::Number)
    cmp(<, x, y)
    convert(T::Type{<:FilePathsBase.AbstractPath}, x::AbstractString)
    map!(::typeof(Core.Compiler.:(==)), dest::Core.Compiler.BitArray, A::Core.Compiler.BitArray, B::Core.Compiler.BitArray)
    map!(::typeof(Core.Compiler.:<), dest::Core.Compiler.BitArray, A::Core.Compiler.BitArray, B::Core.Compiler.BitArray)
    map(::Union{typeof(max), typeof(|)}, A::BitArray, B::BitArray)
    map(f, ::Tuple{}...)
    round(::Type{>:Missing}, ::Missing)
    rowaccess(::Type{<:Union{DataFrames.DataFrameColumns, DataFrames.DataFrameRows}})
    show(io::IO, ::MIME{Symbol(\"application/x-latex\")}, s::LaTeXStrings.LaTeXString)
    tryparsenext(d::Dates.DatePart{'E'}, source, pos, len, b, code, locale)
    var\"#AnsiTextCell#106\"(context::Tuple, ::Type{PrettyTables.AnsiTextCell}, renderfn::Function)
    ⊑(::Core.Compiler.JLTypeLattice, a::Type, b::Type)
     broadcast(::typeof(*), x::Vector{Tx}, y::Vector{Ty}) where {Tx, Ty}
     function two(a::A{Int32}, b::Int32)
     function a(x::D{K, V}, y::Union{B1{K, V}, B2{K, V}} where {K, V}, w::Union{B1{K, V}, B2{K, V}}) where {K, V}
     function _tl(t::Type{<:Tuple})
     function ch(A::B{v} where v<:Union{Int32}, ::Type{A})
    """;

    static void parseArgs(String[] args) {
        for (var arg : args) {
            if (arg.startsWith("-d")) { // debug
                debug = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-h")) { // debug
                PRINT_HIERARCHY = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-s")) { // debug
                SHORTEN = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-i")) { // debug
                NO_CLOSURES = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-d")) { // debug
                debug = arg.substring(3).strip().equals("TRUE");
            } else if (arg.startsWith("-r")) {  // root directory
                dir = arg.substring(3).strip();
            } else if (arg.startsWith("-t")) {
                types = arg.substring(3).strip();
            } else if (arg.startsWith("-f")) {
                functions = arg.substring(3).strip();
            } else if (arg.startsWith("-c")) { // Color mode
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
}
