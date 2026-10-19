package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import java.math.BigInteger;
import java.util.*;

/** Budget-aware search, sharing one exact placement index across each trajectory. */
public final class GeneralSolver {
    public enum Mode { PORTFOLIO, ADAPTIVE, COMPETITION }
    public record Settings(Mode mode, long budgetMillis, long seed, boolean repack,
            boolean exchange, boolean diversify, String starts, boolean verify, int maxUnits) {
        public Settings {
            if (budgetMillis < 1 || budgetMillis > 86_400_000 || maxUnits < 1)
                throw new IllegalArgumentException("Budget must be 1..86400000 ms; units positive");
            if (!List.of("all", "dynamic", "static", "zero").contains(starts))
                throw new IllegalArgumentException("Unknown constructor selection");
        }
    }
    public record Event(int unit, String start, String operation, long delta, long bestRaw,
            long elapsedNanos, int accepted) { }
    public record Result(Solution solution, long raw, long elapsedNanos, long constructionNanos,
            int distinctStarts, int duplicates, int accepted, String termination, List<Event> trace) { }

    private ProblemInstance problem;
    private Settings settings;
    private long start, deadline, bestRaw, constructionNanos;
    private Solution best;
    private int units, accepted, duplicates;
    private int failedDiversifications;
    private final List<Event> trace = new ArrayList<>();
    private final List<Arm> arms = new ArrayList<>();
    private final Set<BitSet> startsSeen = new HashSet<>();
    private Random random;

    public Result solve(ProblemInstance p, Settings config, Solution incumbent) {
        trace.clear(); arms.clear(); startsSeen.clear();
        units=0; accepted=0; duplicates=0; constructionNanos=0; failedDiversifications=0;
        problem=p; settings=config; start=System.nanoTime();
        deadline=start+config.budgetMillis()*1_000_000L;
        random=new Random(config.seed());
        best=new Solution(p.cacheCount()); bestRaw=0;
        if (incumbent != null) {
            new SolutionValidator().validate(p, incumbent);
            best=copy(p,incumbent); bestRaw=PlacementState.fullRaw(p,best);
            addArm(new PlacementState(p,copy(p,best)), "incumbent");
        }
        List<String> constructors = config.starts().equals("all")
                ? List.of("dynamic","zero","static") : List.of(config.starts());
        // Reserve repair time. Adaptive modes avoid dividing a costly primary start into tiny slices.
        long constructionDeadline=start+(long)(config.budgetMillis()*600_000L);
        for (int i=0; i<constructors.size() && System.nanoTime()<constructionDeadline; i++) {
            long before=System.nanoTime();
            if(config.mode()!=Mode.PORTFOLIO && i>0 && constructionDeadline-before<constructionNanos/i*1.25) break;
            long slice=config.mode()==Mode.PORTFOLIO
                    ? before+(constructionDeadline-before)/(constructors.size()-i) : constructionDeadline;
            PlacementState state=BudgetedConstruction.construct(p,constructors.get(i),slice);
            constructionNanos+=System.nanoTime()-before;
            addArm(state,constructors.get(i));
            checkpoint(state,constructors.get(i),"construction",0,0,state.raw());
        }
        int cursor=0;
        while (System.nanoTime()<deadline && units<config.maxUnits()) {
            List<Arm> live=arms.stream().filter(a -> !a.finished).toList();
            if (live.isEmpty()) {
                if (!config.diversify() || arms.isEmpty() || failedDiversifications>=8) break;
                // A worse working placement is isolated; the best checkpoint is never destroyed.
                Arm perturbed=perturb();
                if (perturbed==null) break;
                arms.add(perturbed);
                live=List.of(perturbed);
            }
            Arm selected;
            if (config.mode()==Mode.PORTFOLIO) selected=live.get(cursor++%live.size());
            else {
                // Periodic exploration; otherwise reward recent exact improvement per millisecond.
                if (units%8==0) selected=live.stream().min(Comparator.comparingInt(a -> a.pulls)).orElseThrow();
                else selected=live.stream().max(Comparator.comparingDouble(a -> utility(a))).orElseThrow();
            }
            long before=System.nanoTime(), rawBefore=selected.raw;
            String operation=selected.operation();
            int moves=selected.step();
            long nanos=System.nanoTime()-before;
            long delta=selected.raw-rawBefore;
            if (delta<0) throw new IllegalStateException("A repair step regressed");
            selected.pulls++;
            selected.reward=0.75*selected.reward+0.25*delta/Math.max(1.0,nanos/1e6);
            accepted+=moves; units++;
            checkpoint(selected.state,selected.name,operation,delta,moves,selected.raw);
        }
        new SolutionValidator().validate(p,best);
        if (PlacementState.fullRaw(p,best)!=bestRaw) throw new IllegalStateException("Best checkpoint mismatch");
        return new Result(best,bestRaw,System.nanoTime()-start,constructionNanos,startsSeen.size(),duplicates,
                accepted,units>=config.maxUnits()?"unit_limit":System.nanoTime()>=deadline?"time_budget":"candidate_stagnation",
                List.copyOf(trace));
    }

    private double utility(Arm a) {
        double quality=a.raw/(double)Math.max(1,bestRaw);
        return quality + 0.2*a.reward/(1+Math.abs(a.reward)) + 0.1/Math.sqrt(1+a.pulls);
    }

    private void addArm(PlacementState state,String name) {
        BitSet signature=new BitSet(state.contains.length);
        for (int i=0;i<state.contains.length;i++) if(state.contains[i]) signature.set(i);
        if (!startsSeen.add(signature)) { duplicates++; return; }
        arms.add(new Arm(state,name));
    }

    private void checkpoint(PlacementState state,String name,String operation,long delta,int moves,long raw) {
        if (settings.verify() && PlacementState.fullRaw(problem,state.solution)!=raw)
            throw new AssertionError("Incremental state mismatch");
        if (raw>bestRaw) { bestRaw=raw; best=copy(problem,state.solution); failedDiversifications=0; }
        trace.add(new Event(units,name,operation,delta,bestRaw,System.nanoTime()-start,moves));
    }

    private Arm perturb() {
        if (System.nanoTime()+2_000_000>=deadline) return null;
        PlacementState state=new PlacementState(problem,copy(problem,best));
        int target=random.nextInt(state.caches);
        List<Integer> stored=new ArrayList<>(state.solution.videosInCache(target));
        if (stored.isEmpty()) return null;
        Collections.shuffle(stored,random);
        int remove=Math.max(1,stored.size()/8);
        for (int i=0;i<remove;i++) { int v=stored.get(i); state.remove(target,v); state.refresh(v); }
        Arm arm=new Arm(state,"repair-"+arms.size());
        failedDiversifications++;
        arm.cache=target; arm.phase=1;
        trace.add(new Event(units,arm.name,"perturb",state.raw()-bestRaw,bestRaw,System.nanoTime()-start,0));
        return arm;
    }

    public static Solution copy(ProblemInstance p,Solution original) {
        Solution result=new Solution(p.cacheCount());
        for(int c=0;c<p.cacheCount();c++) for(int v:original.videosInCache(c)) result.addVideo(c,v);
        return result;
    }

    public static long score(ProblemInstance p,long raw) {
        return BigInteger.valueOf(raw).multiply(BigInteger.valueOf(1000))
                .divide(BigInteger.valueOf(p.totalRequestCount())).longValueExact();
    }

    private final class Arm {
        final PlacementState state;
        final String name;
        long raw;
        int cache,phase,passes,passMoves,pulls,pairIndex,exchangeRounds;
        boolean finished;
        double reward;
        final List<int[]> pairs=new ArrayList<>();
        // Valid only until a placement changes; avoids rebuilding a full slot*C index per batch.
        final Map<Long,long[]> transferRows=new HashMap<>();
        final long[] popularity;
        Arm(PlacementState state,String name) {
            this.state=state; this.name=name; raw=state.raw();
            popularity=new long[state.videos];
            for(int v=0;v<state.videos;v++)for(PlacementState.Demand d:state.demands[v])popularity[v]+=d.count;
            for(int a=0;a<state.caches;a++) for(int b=a+1;b<state.caches;b++) pairs.add(new int[]{a,b});
            Collections.shuffle(pairs,new Random(settings.seed()));
        }
        String operation(){ return phase==0?"local":phase==1?"repack":"exchange"; }
        int step() {
            if (phase==2) return exchange();
            int moves=phase==0?local(cache):settings.repack()?repack(cache):0;
            passMoves+=moves;
            if (++cache==state.caches) {
                cache=0; passes++;
                // Keep one complementary conservative trajectory; early repacking is not always better.
                if(passMoves==0 || passes>=(phase==0?(settings.mode()==Mode.PORTFOLIO||name.equals("static")?10:1):settings.mode()==Mode.COMPETITION?12:5)) {
                    passes=0;
                    phase++;
                    if(phase==2 && !settings.exchange()) finished=true;
                }
                passMoves=0;
            }
            return moves;
        }
        int local(int c) {
            long[] benefit=new long[state.videos]; state.benefits(c,benefit);
            List<Integer> stored=new ArrayList<>(state.solution.videosInCache(c));
            stored.sort(Comparator.<Integer>comparingLong(v -> benefit[v]).thenComparingInt(v -> v));
            // Exact cheapest feasible removal by required size; replaces the profiled O(V*stored) scan.
            int[] removal=new int[1002];Arrays.fill(removal,-1);
            for(int v:stored)if(removal[problem.videoSizes()[v]]<0)removal[problem.videoSizes()[v]]=v;
            for(int size=1000;size>=1;size--) {
                int next=removal[size+1],current=removal[size];
                if(next>=0&&(current<0||benefit[next]<benefit[current]||benefit[next]==benefit[current]&&next<current))removal[size]=next;
            }
            int remaining=problem.cacheCapacity()-state.used[c], add=-1,remove=-1;
            long delta=0;
            for(int v=0;v<state.videos;v++) {
                if((v&255)==0 && System.nanoTime()>=deadline) break;
                if(state.has(c,v)||benefit[v]<=0) continue;
                int removed=-1; long loss=0;
                if(problem.videoSizes()[v]>remaining) {
                    int required=problem.videoSizes()[v]-remaining;
                    if(required>1000||removal[required]<0)continue;
                    removed=removal[required];loss=benefit[removed];
                }
                if(benefit[v]-loss>delta) {delta=benefit[v]-loss;add=v;remove=removed;}
            }
            if(add<0) return 0;
            if(remove>=0) state.remove(c,remove);
            state.add(c,add);
            if(remove>=0) state.refresh(remove);
            state.refresh(add); applied(delta); return 1;
        }
        int repack(int c) {
            long[] benefit=new long[state.videos]; state.benefits(c,benefit);
            List<Integer> items=new ArrayList<>(state.solution.videosInCache(c)); Collections.sort(items);
            long incumbent=0;for(int v:items) incumbent+=benefit[v];
            List<Integer> absent=new ArrayList<>();
            for(int v=0;v<state.videos;v++) if(!state.has(c,v)&&benefit[v]>0&&problem.videoSizes()[v]<=problem.cacheCapacity()) absent.add(v);
            absent.sort(Comparator.<Integer>comparingDouble(v -> benefit[v]/(double)problem.videoSizes()[v]).reversed().thenComparingInt(v->v));
            int k=settings.mode()==Mode.COMPETITION?128:64;
            items.addAll(absent.subList(0,Math.min(k,absent.size())));
            int capacity=problem.cacheCapacity();
            // Bound transient DP decisions to 64 MiB. This is a resource guard, not a local optimum.
            if((long)items.size()*(capacity+1)>536_870_912L) return 0;
            long[] dp=new long[capacity+1]; BitSet[] take=new BitSet[items.size()];
            for(int i=0;i<items.size();i++) {
                if(System.nanoTime()>=deadline) return 0; // Discard incomplete DP, never its incumbent.
                int v=items.get(i),size=problem.videoSizes()[v]; take[i]=new BitSet(capacity+1);
                for(int x=capacity;x>=size;x--) {
                    if((x&4095)==0&&System.nanoTime()>=deadline) return 0;
                    long value=dp[x-size]+benefit[v];
                    if(value>dp[x]) {dp[x]=value;take[i].set(x);}
                }
            }
            long delta=dp[capacity]-incumbent;if(delta<=0)return 0;
            BitSet chosen=new BitSet(state.videos);
            for(int i=items.size()-1;i>=0;i--) if(take[i].get(capacity)) {
                int v=items.get(i);chosen.set(v);capacity-=problem.videoSizes()[v];
            }
            List<Integer> changed=new ArrayList<>();
            for(int v:new ArrayList<>(state.solution.videosInCache(c))) if(!chosen.get(v)) {state.remove(c,v);changed.add(v);}
            for(int v=chosen.nextSetBit(0);v>=0;v=chosen.nextSetBit(v+1)) if(!state.has(c,v)){state.add(c,v);changed.add(v);}
            for(int v:changed)state.refresh(v);
            applied(delta);return 1;
        }
        long[] transfers(int c,int v) {
            if(transferRows.size()>40000)transferRows.clear();
            long key=(long)c*state.videos+v;
            return transferRows.computeIfAbsent(key,ignored->{long[] row=new long[state.caches];state.transfers(v,c,row,0);return row;});
        }
        int[] selected(int c) {
            return state.solution.videosInCache(c).stream().sorted(Comparator.<Integer>comparingLong(v -> popularity[v])
                    .reversed().thenComparingInt(v->v)).limit(64).mapToInt(v->v).toArray();
        }
        int exchange() {
            if(pairs.isEmpty()||pairIndex>=Math.min(1000,pairs.size())) {
                if(passMoves==0||++exchangeRounds>=(settings.mode()==Mode.COMPETITION?20:5)) {finished=true;return 0;}
                passMoves=0;pairIndex=0;phase=0;passes=0;return 0;
            }
            int[] pair=pairs.get(pairIndex++);int a=pair[0],b=pair[1];
            long delta=0;int va=-1,vb=-1;
            int[] as=selected(a),bs=selected(b);
            for(int v:as) {
                if(System.nanoTime()>=deadline)break;
                for(int w:bs) {
                    if(v==w||state.has(a,w)||state.has(b,v)
                            ||state.used[a]-problem.videoSizes()[v]+problem.videoSizes()[w]>problem.cacheCapacity()
                            ||state.used[b]-problem.videoSizes()[w]+problem.videoSizes()[v]>problem.cacheCapacity())continue;
                    long value=transfers(a,v)[b]+transfers(b,w)[a];
                    if(value>delta){delta=value;va=v;vb=w;}
                }
            }
            if(va<0)return 0;
            state.remove(a,va);state.remove(b,vb);state.add(a,vb);state.add(b,va);
            state.refresh(va);state.refresh(vb);applied(delta);passMoves++;
            return 1;
        }
        void applied(long delta) {
            raw+=delta;transferRows.clear();
            if(settings.verify()&&PlacementState.fullRaw(problem,state.solution)!=raw)
                throw new AssertionError("Exact move delta mismatch");
        }
    }
}
