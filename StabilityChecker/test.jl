imports = isempty(ARGS) ? error("Required argument: path of imports.jl") : joinpath(pwd(), popfirst!(ARGS))
tests = isempty(ARGS) ? error("Required argument: path of tests.jl") : joinpath(pwd(), popfirst!(ARGS))

const env = "JuliaGenerable"

import Pkg

Pkg.activate(env; shared=true)

mutable struct Stats
    ok::Int
    fail::Int
    stable::Int
    unstable::Int
end
Stats() = Stats(0, 0, 0, 0)
Base.show(io::IO, stats::Stats) = begin
    total = stats.ok + stats.fail
    println(io, "Passed $(stats.ok / total * 100.0)% of $total tests")
    println(io, "      PASSED: $(stats.ok)")
    println(io, "      FAILED: $(stats.fail)")
    println(io, "      STABLE: $(stats.stable)")
    println(io, "    UNSTABLE: $(stats.unstable)")
end
const stats = Stats()

isstablecall(@nospecialize(f::Function), @nospecialize(ts::Vector)) = begin
    try
        ct = code_typed(f, (ts...,), optimize=false)
        length(ct) == 1 || return false
        (_, t) = first(ct)
        res = isconcretetype(t)
        global stats.ok += 1
        if res
            global stats.stable += 1
        else
            global stats.unstable += 1
        end
        return res
    catch e
        println("ERROR:");
        showerror(stdout, e);
        println();
        global stats.fail += 1;
    end
end

include(imports)

println("Running tests from `$tests'")
include(tests)

println("Results: $stats")
