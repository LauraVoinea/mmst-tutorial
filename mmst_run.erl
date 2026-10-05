%% mmst_run: compile and run a set of modules for the playground.
%%
%%   erl -noshell -noinput -nostick -pa <dir> -run mmst_run main <ms> <src dir> <tag>
%%
%% Roles start from the *_sup child specs, else every module implementing a gen_
%% behaviour. "@@<tag> " lines are for the server; the rest is role output.
-module(mmst_run).
-export([main/1, parse_transform/2]).

main([MsS, Src, Tag]) ->
    put(tag, Tag),
    process_flag(trap_exit, true),
    quiet_logger(),
    Files = filelib:wildcard(filename:join(Src, "*.erl")),
    Mods = [module(F) || F <- Files],
    case clashes(Mods) of
        {[], Renames} ->
            [say("renamed ~s ~s~n", [From, To]) || {From, To} <- maps:to_list(Renames)],
            T0 = now_ms(),
            case compile_all(Files, Src, Renames) of
                {ok, Loaded} ->
                    say("compiled ~b ~b~n", [length(Loaded), now_ms() - T0]),
                    run(list_to_integer(MsS), Loaded);
                error ->
                    say("failed ~b~n", [now_ms() - T0])
            end;
        {Clash, _} ->
            say("clash~s~n", [[[" ", atom_to_list(M)] || M <- Clash]])
    end,
    say("done~n"),
    erlang:halt(0).

module(File) -> list_to_atom(filename:basename(File, ".erl")).

say(Fmt, Args) -> io:format("@@~s " ++ Fmt, [get(tag) | Args]).
say(Fmt) -> say(Fmt, []).

now_ms() -> erlang:monotonic_time(millisecond).

%% Cap crash reports on huge states.
quiet_logger() ->
    try logger:update_formatter_config(default, #{chars_limit => 6000, depth => 40})
    catch _:_ -> ok
    end,
    ok.

%% Modules already loaded can't be replaced. Clashing gen_ modules (role server ->
%% gen_server) are renamed for the run; any other clash (user, logger) stops it.
%% c (the shell's) may be replaced.
clashes(Mods) ->
    Loaded = [M || M <- Mods, M =/= c, M =/= ?MODULE, erlang:module_loaded(M)]
             ++ [M || M <- Mods, M =:= ?MODULE],
    {Gen, Other} = lists:partition(fun(M) -> lists:prefix("gen_", atom_to_list(M)) end, Loaded),
    Renames = maps:from_list([{M, list_to_atom(atom_to_list(M) ++ "_role")} || M <- Gen]),
    {Other, Renames}.

%% gen_ modules first, loaded as compiled, so callbacks get behaviour checks.
compile_all(Files, Src, Renames) ->
    {Gen, Rest} = lists:partition(fun(F) -> lists:prefix("gen_", filename:basename(F)) end, Files),
    Opts = [binary, return_errors, return_warnings, {i, Src}]
           ++ case maps:size(Renames) of 0 -> []; _ -> [{parse_transform, ?MODULE}, {mmst_renames, Renames}] end,
    Results = [compile_one(F, Opts, Renames) || F <- lists:sort(Gen) ++ lists:sort(Rest)],
    case [x || error <- Results] of
        [] -> {ok, [M || {ok, M} <- Results]};
        _ -> error
    end.

%% Module name must match file name, or the clash check misses it.
compile_one(File, Opts, Renames) ->
    Name = filename:basename(File),
    Expected = maps:get(module(File), Renames, module(File)),
    case compile:file(File, Opts) of
        {ok, Mod, _, Warnings} when Mod =/= Expected ->
            report(warning, Name, Warnings),
            say("error ~s\t0\t0\tmodule ~s must be in ~s.erl~n", [Name, Mod, Mod]),
            error;
        {ok, Mod, Bin, Warnings} ->
            report(warning, Name, Warnings),
            code:purge(Mod),
            case code:load_binary(Mod, File, Bin) of
                {module, Mod} -> {ok, Mod};
                {error, Why} ->
                    say("error ~s\t0\t0\tcannot load ~s: ~0p~n", [Name, Mod, Why]),
                    error
            end;
        {error, Errors, Warnings} ->
            report(error, Name, Errors),
            report(warning, Name, Warnings),
            error
    end.

report(Kind, Name, ByFile) ->
    [say("~s ~s\t~b\t~b\t~ts~n", [Kind, file_of(F, Name), line(Loc), col(Loc), message(Mod, Desc)])
     || {F, Items} <- ByFile, {Loc, Mod, Desc} <- Items],
    ok.

message(Mod, Desc) ->
    try one_line(Mod:format_error(Desc))
    catch _:_ -> short(Desc)
    end.

%% Errors in an included .hrl name that file.
file_of(F, _Name) when is_list(F), F =/= [] -> filename:basename(F);
file_of(_, Name) -> Name.

line({L, _}) when is_integer(L) -> L;
line(L) when is_integer(L) -> L;
line(_) -> 0.
col({_, C}) when is_integer(C) -> C;
col(_) -> 0.

one_line(Text) ->
    S = unicode:characters_to_list(io_lib:format("~ts", [Text])),
    string:trim([case C of $\n -> $\s; $\t -> $\s; _ -> C end || C <- S]).

%% Rename clashing modules everywhere: -module, -behaviour, calls, ?MODULE.
parse_transform(Forms, Opts) ->
    Renames = proplists:get_value(mmst_renames, Opts, #{}),
    rename(Forms, Renames).

rename({atom, A, N}, R) -> {atom, A, maps:get(N, R, N)};
rename({attribute, A, K, N}, R) when K =:= module; K =:= behaviour; K =:= behavior ->
    {attribute, A, K, maps:get(N, R, N)};
rename(T, R) when is_tuple(T) -> list_to_tuple([rename(E, R) || E <- tuple_to_list(T)]);
rename(L, R) when is_list(L) -> [rename(E, R) || E <- L];
rename(X, _) -> X.

%% --- run ---

run(_Ms, []) ->
    say("nothing~n");
run(Ms, Mods) ->
    run_roles(Ms, starts(Mods)).

run_roles(_Ms, []) ->
    say("nothing~n");
run_roles(Ms, Starts) ->
    %% Kill any process past 128 MB of heap.
    erlang:system_flag(max_heap_size, #{size => 16#1000000, kill => true, error_logger => true}),
    erlang:trace(new_processes, true, [procs]),
    say("started~n"),
    T0 = now_ms(),
    Failed = [F || F <- [start(S) || S <- Starts], F =/= ok],
    {Roles, Exits} = wait([], #{}, T0 + Ms, T0),
    erlang:trace(all, false, [procs]),
    say("ran ~b~n", [now_ms() - T0]),
    [say("role ~s failed ~ts~n", [Id, describe(Why)]) || {Id, Why} <- Failed],
    [try outcome(Name, Pid, Exits)
     catch _:_ -> say("role ~s waiting ?\t0\t0~n", [Name])
     end || {Name, Pid} <- Roles],
    ok.

%% The supervisor's children in order, else every implementer of a loaded gen_ behaviour.
starts(Mods) ->
    Sups = [M || M <- Mods, lists:member(supervisor, behaviours(M)),
                 erlang:function_exported(M, init, 1)],
    case lists:append([children(S) || S <- Sups]) of
        [] ->
            Gens = [M || M <- Mods, lists:prefix("gen_", atom_to_list(M))],
            [{M, {M, start_link, []}} || M <- lists:sort(Mods),
                                        lists:any(fun(B) -> lists:member(B, Gens) end, behaviours(M)),
                                        erlang:function_exported(M, start_link, 0)];
        Children -> Children
    end.

behaviours(M) ->
    Attrs = try M:module_info(attributes) catch _:_ -> [] end,
    lists:append([Bs || {K, Bs} <- Attrs, K =:= behaviour orelse K =:= behavior]).

children(Sup) ->
    try Sup:init([]) of
        {ok, {_Flags, Specs}} -> [child(S) || S <- Specs];
        _ -> []
    catch _:_ -> []
    end.

child(#{id := Id, start := MFA}) -> {Id, MFA};
child({Id, MFA, _, _, _, _}) -> {Id, MFA}.

start({Id, {M, F, A}}) ->
    try apply(M, F, A) of
        {ok, _Pid} -> ok;
        {ok, _Pid, _} -> ok;
        ignore -> ok;
        Other -> {Id, Other}
    catch C:E:St -> {Id, {C, E, St}}
    end.

%% A role is any process that registers a name. Done when all have stopped, or at the deadline.
wait(Order, Exits, Deadline, T0) ->
    Now = now_ms(),
    Live = [P || {_, P} <- Order, not maps:is_key(P, Exits)],
    if
        Now >= Deadline -> {lists:reverse(Order), Exits};
        Order =/= [], Live =:= [], Now - T0 > 50 ->
            %% grace for a role started on the way out
            receive
                {trace, P, register, Name} -> announce(Name, P), wait([{Name, P} | Order], Exits, Deadline, T0)
            after 100 -> {lists:reverse(Order), Exits}
            end;
        true ->
            receive
                {trace, P, register, Name} ->
                    announce(Name, P),
                    wait([{Name, P} | Order], Exits, Deadline, T0);
                {trace, P, exit, Why} ->
                    wait(Order, Exits#{P => Why}, Deadline, T0);
                _ ->
                    wait(Order, Exits, Deadline, T0)
            after min(100, max(0, Deadline - Now)) ->
                wait(Order, Exits, Deadline, T0)
            end
    end.

%% The gen_ module behind a registered name.
announce(Name, Pid) ->
    case erlang:process_info(Pid, dictionary) of
        {dictionary, D} ->
            case proplists:get_value('$initial_call', D) of
                {M, init, 1} -> say("as ~s ~s~n", [Name, M]);
                _ -> ok
            end;
        _ -> ok                                  % already gone
    end.

outcome(Name, Pid, Exits) ->
    case maps:find(Pid, Exits) of
        {ok, normal} -> say("role ~s finished~n", [Name]);
        {ok, shutdown} -> say("role ~s finished~n", [Name]);
        {ok, {shutdown, _}} -> say("role ~s finished~n", [Name]);
        {ok, Why} -> say("role ~s crashed ~ts~n", [Name, describe(Why)]);
        error ->
            {State, Postponed} = status(Pid),
            Queue = case erlang:process_info(Pid, message_queue_len) of
                        {message_queue_len, Q} -> Q;
                        _ -> 0
                    end,
            say("role ~s waiting ~ts\t~b\t~b~n", [Name, State, Queue, Postponed])
    end.

%% gen_statem state and postponed-event count.
status(Pid) ->
    State = try sys:get_state(Pid, 300) of
                {S, _Data} when is_atom(S) -> atom_to_list(S);
                Other -> short(Other)
            catch _:_ -> "?"
            end,
    Postponed = try sys:get_status(Pid, 300) of
                    {status, _, _, Items} -> postponed(Items)
                catch _:_ -> 0
                end,
    {State, Postponed}.

%% From the "Postponed" entry of the formatted status.
postponed([_PDict, _SysState, _Parent, _Debug, Misc]) when is_list(Misc) ->
    case [L || {data, D} <- Misc, is_list(D), {"Postponed", L} <- D, is_list(L)] of
        [L | _] -> length(L);
        [] -> 0
    end;
postponed(_) -> 0.

%% e.g. function_clause in gen_srv:s1/3 (gen_srv.erl, line 61)
describe(Why) ->
    try unicode:characters_to_list(explain(Why))
    catch _:_ -> short(Why)
    end.

explain({Reason, [{M, F, A, Loc} | _]}) when is_atom(M), is_atom(F), is_list(Loc) ->
    Arity = if is_list(A) -> length(A); true -> A end,
    Where = case {proplists:get_value(file, Loc), proplists:get_value(line, Loc)} of
                {undefined, _} -> "";
                {File, undefined} -> io_lib:format(" (~s)", [filename:basename(File)]);
                {File, L} -> io_lib:format(" (~s, line ~b)", [filename:basename(File), L])
            end,
    io_lib:format("~ts in ~s:~s/~b~s", [short(Reason), M, F, Arity, Where]);
explain({error, E, [_ | _] = St}) -> explain({E, St});
explain({C, E, [_ | _] = St}) when C =:= exit; C =:= throw -> explain({{C, E}, St});
explain(Why) -> short(Why).

short(T) -> one_line(io_lib:format("~0tP", [T, 9])).
