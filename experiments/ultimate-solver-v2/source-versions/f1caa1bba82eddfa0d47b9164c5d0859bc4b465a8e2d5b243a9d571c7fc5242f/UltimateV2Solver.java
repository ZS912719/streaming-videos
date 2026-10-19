package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import java.util.*;

/** Deadline-aware, shared-state search. Historical solvers are deliberately untouched. */
public final class UltimateV2Solver {
    public enum Policy { FIXED, ADAPTIVE, INTENSIVE }
    public record Settings(Policy policy,long millis,long seed,boolean verify,int maxUnits,
                           boolean packing,boolean deepRepack,boolean exchange,boolean complement,boolean pattern,
                           boolean benefitIndex,boolean sizePruning) {
        public Settings {
            Objects.requireNonNull(policy);
            if(millis<1||millis>86_400_000||maxUnits<1)throw new IllegalArgumentException("Invalid time/work limit");
        }
    }
    public record Event(int unit,String start,String operation,long delta,long bestRaw,long elapsedNanos,int accepted,long armRaw) { }
    public record Trajectory(String name,long initialRaw,long raw,long repairNanos,int cycle,int phase,boolean finished) { }
    @FunctionalInterface public interface CheckpointSink { void save(int seconds,Solution solution,long raw,long observedNanos,boolean early); }
    public record Result(Solution solution,long raw,long constructionNanos,long searchNanos,long validationNanos,int accepted,
                         int distinctStarts,int duplicates,String termination,List<Event> trace,Map<String,Long> operationNanos,List<Trajectory> trajectories) { }
    private ProblemInstance p;
    private Settings settings;
    private long start,deadline,bestRaw,constructionNanos;
    private Solution best;
    private Arm leader;
    private int units,moves,duplicates;
    private final List<Event> trace=new ArrayList<>();
    private final List<Arm> arms=new ArrayList<>();
    private final Map<String,Long> operationNanos=new TreeMap<>();
    private CheckpointSink checkpointSink;
    private long clockOffset;
    private int checkpointIndex;
    private long repairWork,explorationWork;
    private static final int[] CHECKPOINTS={10,30,60,180,300};

    public Result solve(ProblemInstance problem,Settings config,Solution incumbent) {
        return solve(problem,config,incumbent,null,0);
    }
    public Result solve(ProblemInstance problem,Settings config,Solution incumbent,CheckpointSink sink,long clockOffsetNanos) {
        p=problem;settings=config;start=System.nanoTime();deadline=start+config.millis()*1_000_000L;
        checkpointSink=sink;clockOffset=clockOffsetNanos;checkpointIndex=0;
        repairWork=explorationWork=0;
        units=moves=duplicates=0;constructionNanos=bestRaw=0;trace.clear();arms.clear();operationNanos.clear();leader=null;
        best=new Solution(p.cacheCount());
        if(incumbent!=null) {
            new SolutionValidator().validate(p,incumbent);
            best=GeneralSolver.copy(p,incumbent);bestRaw=PlacementState.fullRaw(p,best);
            Arm a=new Arm(new PlacementState(p,GeneralSolver.copy(p,best)),"incumbent",false);
            a.phase=4;arms.add(a);record(a,"incumbent",0,0);
        }
        Set<BitSet> seen=new HashSet<>();
        long constructionDeadline=start+Math.max(100_000_000L,config.millis()*300_000L);
        for(String kind:List.of("dynamic","zero","static")) {
            if(expired()||System.nanoTime()>=constructionDeadline)break;
            long before=System.nanoTime();
            PlacementState state=BudgetedConstruction.construct(p,kind,
                    Math.min(deadline,constructionDeadline));
            constructionNanos+=System.nanoTime()-before;
            BitSet signature=new BitSet(state.contains.length);
            for(int i=0;i<state.contains.length;i++)if(state.contains[i])signature.set(i);
            if(!seen.add(signature)){duplicates++;continue;}
            Arm a=new Arm(state,kind,false);arms.add(a);record(a,"construction",0,0);
            // One complementary repair trajectory, not another redundant constructor.
            if(kind.equals("dynamic")&&config.packing()&&!expired()) {
                Arm packed=new Arm(new PlacementState(p,GeneralSolver.copy(p,state.solution)),"dynamic-packing",true);
                arms.add(packed);
            }
        }
        int cursor=0;
        while(!expired()&&units<config.maxUnits()) {
            checkpoints(false);
            List<Arm> live=arms.stream().filter(a->!a.finished).toList();
            if(live.isEmpty())break;
            Arm chosen;
            boolean exploring=false;
            if(config.policy()==Policy.FIXED)chosen=live.get(cursor++%live.size());
            else {
                // Protect the reproducible primary pipeline before racing complementary basins.
                long primaryTrial=Math.min(20_000_000_000L,config.millis()*500_000L);
                Arm primary=incumbent==null?live.stream().filter(a->a.name.equals("dynamic")
                        &&(a.cycle==0||a.cycle==1&&a.phase==4&&a.passes==0&&a.spent<primaryTrial)).findFirst().orElse(null):null;
                Arm continuation=live.stream().filter(a->a.name.equals("incumbent")&&a.cycle<7).findFirst().orElse(null);
                List<Arm> unexplored=live.stream().filter(a->a.cycle==0&&a.spent<trial(a)).toList();
                if(continuation!=null)chosen=continuation;
                else if(primary!=null)chosen=primary;
                else if(config.policy()==Policy.INTENSIVE&&unexplored.stream().anyMatch(a->a.packed))
                    chosen=unexplored.stream().filter(a->a.packed).findFirst().orElseThrow();
                else if(!unexplored.isEmpty())chosen=unexplored.stream().min(Comparator.comparingLong(a->a.spent)).orElseThrow();
                else if(explorationWork<repairWork/5) {
                    chosen=live.stream().min(Comparator.comparingLong(a->a.spent)).orElseThrow();exploring=true;
                }
                else chosen=live.stream().max(Comparator.comparingDouble(this::utility)).orElseThrow();
            }
            long before=System.nanoTime(),raw=chosen.raw;
            String operation=chosen.operation();
            int accepted=chosen.step();
            long elapsed=System.nanoTime()-before;
            operationNanos.merge(operation,elapsed,Long::sum);
            chosen.spent+=elapsed;
            repairWork+=elapsed;if(exploring)explorationWork+=elapsed;
            chosen.windowNanos+=elapsed;chosen.windowGain+=chosen.raw-raw;
            // Compare completed cache passes / swap rounds, not a DP with an empty pair scan.
            if(chosen.boundary) {
                chosen.rate=.5*chosen.rate+.5*chosen.windowGain/(double)Math.max(1,chosen.windowNanos);
                chosen.windowNanos=chosen.windowGain=0;
            }
            units++;moves+=accepted;
            // Store accepted moves and phase boundaries, not millions of empty pair scans.
            if(accepted>0||chosen.boundary)record(chosen,operation,chosen.raw-raw,accepted);
        }
        checkpoints(!expired()&&units<config.maxUnits());
        String termination=units>=config.maxUnits()?"unit_limit":expired()?"time_budget":"bounded_neighborhood_stagnation";
        long validationStart=System.nanoTime();
        new SolutionValidator().validate(p,best);
        if(PlacementState.fullRaw(p,best)!=bestRaw)throw new IllegalStateException("Best objective mismatch");
        long validationNanos=System.nanoTime()-validationStart;
        return new Result(best,bestRaw,constructionNanos,validationStart-start-constructionNanos,validationNanos,moves,
                seen.size(),duplicates,termination,List.copyOf(trace),Map.copyOf(operationNanos),
                arms.stream().map(a->new Trajectory(a.name,a.initialRaw,a.raw,a.spent,a.cycle,a.phase,a.finished)).toList());
    }

    private boolean expired(){return System.nanoTime()>=deadline;}
    private long trial(Arm a) {
        return settings.policy()==Policy.INTENSIVE&&a.packed
                ?Math.min(12_000_000_000L,settings.millis()*250_000L)
                :Math.min(5_000_000_000L,settings.millis()*100_000L);
    }
    private double utility(Arm a) {
        // Rate is normalized by objective; it does not saturate on raw request-ms magnitudes.
        return a.raw/(double)Math.max(1,bestRaw)+Math.min(.01,3*a.rate*1e9/Math.max(1,bestRaw))
                +.002/Math.sqrt(1+a.spent/1e9);
    }
    private void record(Arm a,String operation,long delta,int accepted) {
        // Publish the previous best before accepting an event beyond a checkpoint boundary.
        // Thus a long atomic unit cannot receive credit before it actually completed.
        checkpoints(false,a);
        if(settings.verify()&&PlacementState.fullRaw(p,a.state.solution)!=a.raw)throw new AssertionError("Incremental objective mismatch");
        // Every arm is monotone. Retain its live placement instead of copying the
        // entire solution after each tiny improvement. Only timed snapshots need copies.
        if(a.raw>bestRaw){bestRaw=a.raw;best=a.state.solution;leader=a;}
        trace.add(new Event(units,a.name,operation,delta,bestRaw,System.nanoTime()-start,accepted,a.raw));
    }
    private void checkpoints(boolean early) {
        checkpoints(early,null);
    }
    private void checkpoints(boolean early,Arm changedArm) {
        if(checkpointSink==null)return;
        long elapsed=System.nanoTime()-start+clockOffset;
        long configured=clockOffset+settings.millis()*1_000_000L;
        while(checkpointIndex<CHECKPOINTS.length&&CHECKPOINTS[checkpointIndex]*1_000_000_000L<=configured+1_000_000L
                &&(early||elapsed>=CHECKPOINTS[checkpointIndex]*1_000_000_000L)) {
            Solution snapshot=best;
            if(changedArm!=null&&changedArm==leader&&changedArm.raw>bestRaw) {
                snapshot=GeneralSolver.copy(p,best);
                // The just-finished atomic move may straddle the requested time.
                // Undo it in the snapshot only, giving the best known beforehand.
                for(int i=changedArm.undo.size()-1;i>=0;i--) {
                    Edit edit=changedArm.undo.get(i);
                    if(edit.added)snapshot.removeVideo(edit.cache,edit.video);
                    else snapshot.addVideo(edit.cache,edit.video);
                }
            }
            checkpointSink.save(CHECKPOINTS[checkpointIndex++],snapshot,bestRaw,elapsed,early);
        }
    }

    private final class Arm {
        final PlacementState state;
        final String name;
        final boolean packed;
        final long[] popularity;
        final List<int[]> pairs=new ArrayList<>();
        final Map<Long,Row> transfers=new HashMap<>();
        final int[] videoVersion,cacheVersion,localNoMove,packingNoMove;
        final int[][] scanned;
        final List<Edit> undo=new ArrayList<>();
        final BenefitIndex benefitIndex;
        final long[] scratchBenefits;
        long raw,spent,cycleStart;
        final long initialRaw;
        long windowNanos,windowGain;
        double rate;
        int phase,cache,passes,passMoves,cycle,exchangeRounds,pairCursor,exchangeA=-1,exchangeB=-1,exchangeV=-1,exchangeW=-1;
        int placementVersion,lastExchangeNoMove=-1,lastPairCoverage,lastVideoCoverage;
        long exchangeDelta;
        long exchangeTie=Long.MAX_VALUE;
        int[][] selected,bySize;
        boolean finished,boundary;

        Arm(PlacementState s,String name,boolean packed) {
            state=s;this.name=name;this.packed=packed;raw=cycleStart=initialRaw=s.raw();
            benefitIndex=settings.benefitIndex()?new BenefitIndex(state):null;scratchBenefits=new long[s.videos];
            popularity=new long[s.videos];videoVersion=new int[s.videos];cacheVersion=new int[s.caches];
            localNoMove=new int[s.caches];packingNoMove=new int[s.caches];Arrays.fill(localNoMove,-1);Arrays.fill(packingNoMove,-1);
            scanned=new int[4][s.caches];for(int[] x:scanned)Arrays.fill(x,-1);
            for(int v=0;v<s.videos;v++)for(PlacementState.Demand d:s.demands[v])popularity[v]+=d.count;
            for(int a=0;a<s.caches;a++)for(int b=a+1;b<s.caches;b++)pairs.add(new int[]{a,b});
            Collections.shuffle(pairs,new Random(settings.seed()));
        }
        String operation(){return phase==0||phase==3?"local":phase==1?(packed?"packing":"repack64"):phase==2?"exchange-global":phase==5?"pattern":"repack"+pool();}
        int pool(){return !settings.deepRepack()?64:cycle<2?32:cycle<4?64:cycle<6?128:settings.policy()==Policy.INTENSIVE?512:256;}
        int step() {
            boundary=false;undo.clear();
            if(phase==2)return exchange();
            int c=cache;
            int accepted;
            if(phase==0||phase==3)accepted=local(c);
            else if(phase==1&&packed)accepted=packing(c);
            else if(phase==5)accepted=pattern(c);
            else if(phase==1&&!prefixRepackAllowed())accepted=0;
            else accepted=repack(c,phase==1?64:pool());
            passMoves+=accepted;
            if(++cache==state.caches) {
                boundary=true;cache=0;passes++;
                int cap=phase==0||phase==3?(packed?50:10):phase==1?(packed?10:5):phase==5?1:8;
                if(passMoves==0||passes>=cap) {
                    passes=0;phase++;passMoves=0;
                    if(phase==2&&!settings.exchange())phase=3;
                    if(phase==4&&cycle==0){cycle++;cycleStart=raw;}
                    if(phase==5&&!settings.pattern())phase=6;
                    if(phase==6) {
                        cycle++;
                        // Repeat useful neighborhoods; expand K before declaring bounded stagnation.
                        if(raw==cycleStart&&(!settings.deepRepack()||cycle>=8))finished=true;
                        cycleStart=raw;phase=packed?1:2;
                        if(!settings.exchange())phase=3;
                    }
                }else passMoves=0;
            }
            return accepted;
        }
        void add(int c,int v){state.add(c,v);cacheVersion[c]++;undo.add(new Edit(c,v,true));}
        void remove(int c,int v){state.remove(c,v);cacheVersion[c]++;undo.add(new Edit(c,v,false));}
        long[] benefits(int c) {
            if(benefitIndex==null)state.benefits(c,scratchBenefits);else benefitIndex.row(c,scratchBenefits);
            return scratchBenefits;
        }
        int pattern(int c) {
            long[] b=benefits(c);
            long incumbent=0;for(int v:state.solution.videosInCache(c))incumbent+=b[v];
            long delta=0;Arm donor=null;
            for(Arm other:arms)if(other!=this) {
                long value=0;for(int v:other.state.solution.videosInCache(c))value+=b[v];
                if(value-incumbent>delta){delta=value-incumbent;donor=other;}
            }
            if(donor==null)return 0;
            Set<Integer> chosen=new HashSet<>(donor.state.solution.videosInCache(c));
            List<Integer> changed=new ArrayList<>();
            for(int v:new ArrayList<>(state.solution.videosInCache(c)))if(!chosen.contains(v)){remove(c,v);changed.add(v);}
            for(int v:new TreeSet<>(chosen))if(!state.has(c,v)){add(c,v);changed.add(v);}
            changed(delta,changed.stream().mapToInt(v->v).toArray());return 1;
        }
        boolean prefixRepackAllowed() {
            long cells=0;
            for(int c=0;c<state.caches;c++)cells+=(long)(state.solution.videosInCache(c).size()+64)*p.cacheCapacity()*5;
            return cells<=3_000_000_000L;
        }
        void changed(long delta,int... videos) {
            raw+=delta;placementVersion++;
            BitSet affected=new BitSet(state.caches);
            for(int v:videos)if(v>=0) {
                state.refresh(v);videoVersion[v]++;
                if(benefitIndex!=null)benefitIndex.refresh(v);
                for(PlacementState.Demand d:state.demands[v])for(var link:p.endpoints().get(d.endpoint).connections())affected.set(link.cacheId());
            }
            for(int c=affected.nextSetBit(0);c>=0;c=affected.nextSetBit(c+1))cacheVersion[c]++;
            if(settings.verify()&&benefitIndex!=null)benefitIndex.verify();
            if(settings.verify()&&PlacementState.fullRaw(p,state.solution)!=raw)throw new AssertionError("Move delta mismatch");
        }
        int local(int c) {
            if(localNoMove[c]==cacheVersion[c])return 0;
            long[] b=benefits(c);
            int[] remove=new int[1002];Arrays.fill(remove,-1);
            for(int v:state.solution.videosInCache(c)) {
                int size=p.videoSizes()[v],old=remove[size];
                if(old<0||b[v]<b[old]||b[v]==b[old]&&v<old)remove[size]=v;
            }
            for(int size=1000;size>=0;size--) {
                int next=remove[size+1],old=remove[size];
                if(next>=0&&(old<0||b[next]<b[old]||b[next]==b[old]&&next<old))remove[size]=next;
            }
            int free=p.cacheCapacity()-state.used[c],add=-1,removed=-1;long delta=0;
            for(int v=0;v<state.videos;v++) {
                if((v&255)==0&&expired())break;
                if(state.has(c,v)||b[v]<=0)continue;
                int need=p.videoSizes()[v]-free,r=need<=0?-1:remove[need];
                if(need>0&&r<0)continue;
                long value=b[v]-(r<0?0:b[r]);
                if(value>delta){delta=value;add=v;removed=r;}
            }
            if(add<0){if(!expired())localNoMove[c]=cacheVersion[c];return 0;}
            if(removed>=0)remove(c,removed);add(c,add);changed(delta,add,removed);return 1;
        }
        int packing(int c) {
            if(packingNoMove[c]==cacheVersion[c])return 0;
            long[] b=benefits(c);
            List<Integer> stored=new ArrayList<>(state.solution.videosInCache(c));Collections.sort(stored);
            int bound=Math.min(p.cacheCapacity(),2000),free=p.cacheCapacity()-state.used[c];
            long inf=Long.MAX_VALUE/4;long[] one=new long[bound+1],two=new long[bound+1];
            int[] single=new int[bound+1],pa=new int[bound+1],pb=new int[bound+1];
            Arrays.fill(one,inf);Arrays.fill(two,inf);
            for(int i=0;i<stored.size();i++) {
                if(expired())return 0;
                int a=stored.get(i),s=p.videoSizes()[a];
                if(b[a]<one[s]){one[s]=b[a];single[s]=a;}
                for(int j=i+1;j<stored.size();j++) {
                    int v=stored.get(j),sum=s+p.videoSizes()[v];
                    if(sum<=bound&&b[a]+b[v]<two[sum]){two[sum]=b[a]+b[v];pa[sum]=a;pb[sum]=v;}
                }
            }
            for(int x=bound-1;x>=0;x--) {
                if(one[x+1]<one[x]){one[x]=one[x+1];single[x]=single[x+1];}
                if(two[x+1]<two[x]){two[x]=two[x+1];pa[x]=pa[x+1];pb[x]=pb[x+1];}
            }
            long delta=0;int ra=-1,rb=-1,aa=-1,ab=-1;
            List<Integer> absent=new ArrayList<>();
            for(int v=0;v<state.videos;v++)if(!state.has(c,v)&&b[v]>0&&p.videoSizes()[v]<=p.cacheCapacity()) {
                absent.add(v);int need=p.videoSizes()[v]-free;
                if(need>0&&need<=bound&&two[need]<inf&&b[v]-two[need]>delta){delta=b[v]-two[need];ra=pa[need];rb=pb[need];aa=v;}
            }
            absent.sort(Comparator.<Integer>comparingDouble(v->b[v]/(double)p.videoSizes()[v]).reversed().thenComparingInt(v->v));
            for(int i=0;i<Math.min(64,absent.size());i++)for(int j=i+1;j<Math.min(64,absent.size());j++) {
                int a=absent.get(i),v=absent.get(j),need=Math.max(0,p.videoSizes()[a]+p.videoSizes()[v]-free);
                if(need>bound||need>0&&one[need]==inf)continue;
                long value=b[a]+b[v]-(need>0?one[need]:0);
                if(value>delta){delta=value;ra=need>0?single[need]:-1;rb=-1;aa=a;ab=v;}
            }
            if(aa<0){if(!expired())packingNoMove[c]=cacheVersion[c];return 0;}
            if(ra>=0)remove(c,ra);if(rb>=0)remove(c,rb);
            add(c,aa);if(ab>=0)add(c,ab);changed(delta,ra,rb,aa,ab);return 1;
        }
        int repack(int c,int k) {
            int slot=k==32?0:k==64?1:k==128?2:3;
            if(cycle>0&&scanned[slot][c]==cacheVersion[c])return 0;
            long[] b=benefits(c);
            List<Integer> items=new ArrayList<>(state.solution.videosInCache(c));Collections.sort(items);
            long incumbent=0;for(int v:items)incumbent+=b[v];
            List<Integer> absent=new ArrayList<>();
            for(int v=0;v<state.videos;v++)if(!state.has(c,v)&&b[v]>0&&p.videoSizes()[v]<=p.cacheCapacity())absent.add(v);
            absent.sort(Comparator.<Integer>comparingDouble(v->b[v]/(double)p.videoSizes()[v]).reversed().thenComparingInt(v->v));
            items.addAll(absent.subList(0,Math.min(k,absent.size())));
            int total=0,max=0;long value=0;
            for(int v:items){total+=p.videoSizes()[v];max=Math.max(max,p.videoSizes()[v]);value+=b[v];}
            if(value==incumbent){scanned[slot][c]=cacheVersion[c];return 0;}
            int capacity=Math.min(total,p.cacheCapacity());
            boolean complement=settings.complement()&&cycle>0&&total-capacity+max<capacity;
            int limit=complement?Math.min(total,total-capacity+Math.max(0,max-1)):capacity;
            if((long)items.size()*(limit+1)>536_870_912L)return 0;
            long[] dp=new long[limit+1];BitSet[] take=new BitSet[items.size()];
            if(complement){Arrays.fill(dp,Long.MAX_VALUE/4);dp[0]=0;}
            for(int i=0;i<items.size();i++) {
                if(expired())return 0;
                int v=items.get(i),size=p.videoSizes()[v];take[i]=new BitSet(limit+1);
                for(int x=limit;x>=size;x--) {
                    if((x&4095)==0&&expired())return 0;
                    long next=dp[x-size]+b[v];
                    if(complement?next<dp[x]:next>dp[x]){dp[x]=next;take[i].set(x);}
                }
            }
            int x=limit;
            if(complement){x=total-capacity;for(int i=x+1;i<=limit;i++)if(dp[i]<dp[x])x=i;value-=dp[x];}
            else value=dp[limit];
            long delta=value-incumbent;
            scanned[slot][c]=cacheVersion[c];
            if(delta<=0)return 0;
            BitSet chosen=new BitSet(state.videos);
            if(complement)for(int v:items)chosen.set(v);
            for(int i=items.size()-1;i>=0;i--)if(take[i].get(x)) {
                int v=items.get(i);chosen.set(v,!complement);x-=p.videoSizes()[v];
            }
            List<Integer> changed=new ArrayList<>();
            for(int v:new ArrayList<>(state.solution.videosInCache(c)))if(!chosen.get(v)){remove(c,v);changed.add(v);}
            for(int v=chosen.nextSetBit(0);v>=0;v=chosen.nextSetBit(v+1))if(!state.has(c,v)){add(c,v);changed.add(v);}
            changed(delta,changed.stream().mapToInt(v->v).toArray());return 1;
        }
        long[] row(int c,int v) {
            long key=(long)c*state.videos+v;Row r=transfers.get(key);
            if(r==null||r.version!=videoVersion[v]) {
                if((long)transfers.size()*state.caches>16_000_000L)transfers.clear();
                long[] values=new long[state.caches];state.transfers(v,c,values,0);
                r=new Row(videoVersion[v],values);transfers.put(key,r);
            }
            return r.values;
        }
        int exchange() {
            if(!settings.exchange()){phase=3;return 0;}
            int pairLimit=packed&&cycle==0?pairs.size():cycle>=2?Math.min(5000,pairs.size()):Math.min(1000,pairs.size());
            int count=packed&&cycle==0?Integer.MAX_VALUE:cycle>=4?128:64;
            if(selected==null) {
                if(lastExchangeNoMove==placementVersion&&lastPairCoverage>=pairLimit&&lastVideoCoverage>=count) {
                    phase=3;exchangeRounds=0;boundary=true;return 0;
                }
                selected=new int[state.caches][];
                bySize=new int[state.caches][];
                for(int c=0;c<state.caches;c++)selected[c]=state.solution.videosInCache(c).stream()
                        .sorted(Comparator.<Integer>comparingLong(v->popularity[v]).reversed().thenComparingInt(v->v))
                        .limit(count).mapToInt(v->v).toArray();
                for(int c=0;c<state.caches;c++) {
                    final int cache=c;
                    Integer[] ranks=new Integer[selected[c].length];for(int i=0;i<ranks.length;i++)ranks[i]=i;
                    if(settings.sizePruning())Arrays.sort(ranks,Comparator.<Integer>comparingInt(i->p.videoSizes()[selected[cache][i]]).thenComparingInt(i->i));
                    bySize[c]=Arrays.stream(ranks).mapToInt(i->i).toArray();
                }
            }
            if(pairCursor<pairLimit) {
                int rank=pairCursor;int[] pair=pairs.get(pairCursor++);int a=pair[0],b=pair[1];
                for(int ai=0;ai<selected[a].length;ai++) {
                    int v=selected[a][ai];
                    if(expired())break;
                    long[] av=null;
                    int lower=p.videoSizes()[v]-(p.cacheCapacity()-state.used[b]);
                    int upper=p.videoSizes()[v]+p.cacheCapacity()-state.used[a];
                    int begin=0;
                    if(settings.sizePruning()) {
                        int hi=bySize[b].length;
                        while(begin<hi) {int mid=(begin+hi)>>>1;if(p.videoSizes()[selected[b][bySize[b][mid]]]<lower)begin=mid+1;else hi=mid;}
                    }
                    for(int i=begin;i<bySize[b].length;i++) {
                        int bi=bySize[b][i],w=selected[b][bi];
                        if(settings.sizePruning()&&p.videoSizes()[w]>upper)break;
                        if(v==w||state.has(a,w)||state.has(b,v)||state.used[a]-p.videoSizes()[v]+p.videoSizes()[w]>p.cacheCapacity()
                                ||state.used[b]-p.videoSizes()[w]+p.videoSizes()[v]>p.cacheCapacity())continue;
                        if(av==null)av=row(a,v);
                        long delta=av[b]+row(b,w)[a];
                        // Preserve seeded pair/popularity-rank ties despite size-ordered evaluation.
                        long tie=((long)rank*state.videos+ai)*state.videos+bi;
                        if(delta>exchangeDelta||delta>0&&delta==exchangeDelta&&tie<exchangeTie) {
                            exchangeDelta=delta;exchangeTie=tie;exchangeA=a;exchangeB=b;exchangeV=v;exchangeW=w;
                        }
                    }
                }
                return 0;
            }
            int accepted=0;
            if(exchangeDelta>0) {
                remove(exchangeA,exchangeV);remove(exchangeB,exchangeW);
                add(exchangeA,exchangeW);add(exchangeB,exchangeV);
                changed(exchangeDelta,exchangeV,exchangeW);accepted=1;
            }else {lastExchangeNoMove=placementVersion;lastPairCoverage=pairLimit;lastVideoCoverage=count;}
            boundary=true;selected=null;pairCursor=0;exchangeRounds++;
            int cap=packed&&cycle==0?20:cycle==0?5:settings.policy()==Policy.INTENSIVE?20:10;
            if(accepted==0||exchangeRounds>=cap){phase=3;exchangeRounds=0;}
            exchangeDelta=0;exchangeTie=Long.MAX_VALUE;return accepted;
        }
    }
    private record Row(int version,long[] values) { }
    private record Edit(int cache,int video,boolean added) { }
}
