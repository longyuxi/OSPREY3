package edu.duke.cs.osprey.sharkstar;

import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.order.DynamicHMeanAStarOrder;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.astar.seq.nodes.SeqAStarNode;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.SeqSpace;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.markstar.framework.StaticBiggestLowerboundDifferenceOrder;
import edu.duke.cs.osprey.tools.BigMath;
import edu.duke.cs.osprey.tools.MathTools;
import edu.duke.cs.osprey.tools.ObjectPool;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Thin wrapper class to play nice with BBK* and MSK*
 */
public class SingleSequenceSHARKStarBound implements PartitionFunction {
    public int maxMinimizations = 1;
    public MultiSequenceSHARKStarBound multiSequenceSHARKStarBound;
    public final Sequence sequence;
    private MultiSequenceSHARKStarBound multisequenceBound;
    private Status status;
    private MultiSequenceSHARKStarBound.Values values;
    private int numConfsEvaluated = 0;
    public final BigInteger numConformations;
    public SHARKStarQueue fringeNodes;
    public SHARKStarQueue internalQueue;
    public SHARKStarQueue leafQueue;
    private double sequenceEpsilon = 1;
    private BigDecimal finishedNodeZ = BigDecimal.ZERO;
    public final RCs seqRCs;
    public final State state;
    private StaticBiggestLowerboundDifferenceOrder order = null;
    //private DynamicHMeanAStarOrder order = null;

    //debug variable
    public Set<MultiSequenceSHARKStarNode> finishedNodes = new HashSet<>();
    private boolean errors;

    public SingleSequenceSHARKStarBound(MultiSequenceSHARKStarBound multiSequenceSHARKStarBound, Sequence seq, MultiSequenceSHARKStarBound sharkStarBound) {
        this.multiSequenceSHARKStarBound = multiSequenceSHARKStarBound;
        this.sequence = seq;
        this.multisequenceBound = sharkStarBound;
        this.seqRCs = seq.makeRCs(sharkStarBound.confSpace);
        this.numConformations = seqRCs.getNumConformations();
        this.fringeNodes = new SHARKStarQueue(seq);
        this.internalQueue = new SHARKStarQueue(seq);
        this.leafQueue = new SHARKStarQueue(seq);

        this.state = new State();
        this.state.bound = this;
        this.state.targetEpsilon = multiSequenceSHARKStarBound.targetEpsilon;
        this.state.lowerBound = BigDecimal.ZERO;
        this.state.upperBound = BigDecimal.ZERO;
    }

    public void addFinishedNode(MultiSequenceSHARKStarNode node) {
        //finishedNodeZ = finishedNodeZ.add(node.getUpperBound(sequence));
        //System.out.println("Adding "+node.toSeqString(sequence)+" to finished set");
        if(finishedNodes.contains(node))
            System.err.println("Dupe node addition.");
        finishedNodes.add(node);
    }

    public StaticBiggestLowerboundDifferenceOrder getOrder(){
    //public DynamicHMeanAStarOrder getOrder(){
        if(this.order == null){
            return multiSequenceSHARKStarBound.order;
        }else{
            return this.order;
        }
    }

    public void makeAlternativeOrder(AStarScorer gscorer, AStarScorer hscorer){
        // Initialize residue ordering
        this.order = new StaticBiggestLowerboundDifferenceOrder();
        //this.order = new DynamicHMeanAStarOrder();
        this.order.setScorers(gscorer, hscorer);
    }

    @Override
    public void setReportProgress(boolean val) {
        multisequenceBound.setReportProgress(val);
    }

    @Override
    public void setConfListener(ConfListener val) {
        multisequenceBound.setConfListener(val);
    }

    @Override
    public void init(ConfSearch confSearch, BigInteger numConfsBeforePruning, double targetEpsilon) {
        init(confSearch, null, numConfsBeforePruning, targetEpsilon);
    }

    @Override
    public void init(ConfSearch upperBoundConfs, ConfSearch lowerBoundConfs, BigInteger numConfsBeforePruning, double targetEpsilon) {
        values = new MultiSequenceSHARKStarBound.Values();
        setStatus(Status.Estimating);
    }


    @Override
    public void setStabilityThreshold(BigDecimal stabilityThreshold) {
        multisequenceBound.setStabilityThreshold(stabilityThreshold);
        this.state.stabilityThreshold = stabilityThreshold;
    }

    @Override
    public Status getStatus() {
        return this.status;
    }

    @Override
    public Values getValues() {
        //return this.values;
        Values values = new Values();
        values.pstar = this.state.getUpperBound();
        values.qstar = this.state.getLowerBound();
        return values;
    }

    @Override
    public int getParallelism() {
        return multisequenceBound.getParallelism();
    }

    @Override
    public int getNumConfsEvaluated() {
        return numConfsEvaluated;
    }

    @Override
    public void compute(int maxNumConfs) {
        multisequenceBound.computeForSequenceParallel(maxNumConfs, this);
    }

    @Override
    public void compute() {
        compute(Integer.MAX_VALUE);
    }

    @Override
    public Result makeResult() {
        //multiSequenceSHARKStarBound.lowerReduction_ConfUpperBound = multiSequenceSHARKStarBound.rootNode.getLowerBound(sequence)
                //.subtract(multiSequenceSHARKStarBound.startLowerBound).subtract(multiSequenceSHARKStarBound.lowerReduction_FullMin);
        // Calculate the lower bound z reductions from conf upper bounds, since we don't explicitly record these
        //multiSequenceSHARKStarBound.upperReduction_ConfLowerBound = multiSequenceSHARKStarBound.startUpperBound.subtract(multiSequenceSHARKStarBound.rootNode.getUpperBound(sequence))
                //.subtract(multiSequenceSHARKStarBound.upperReduction_FullMin).subtract(multiSequenceSHARKStarBound.upperReduction_PartialMin);

        Result result = new Result(getStatus(), getValues(), getNumConfsEvaluated());
        /*
        result.setWorkInfo(numPartialMinimizations, numConfsScored,minList);
        result.setZInfo(lowerReduction_FullMin, lowerReduction_ConfUpperBound, upperReduction_FullMin, upperReduction_PartialMin, upperReduction_ConfLowerBound);
        result.setOrigBounds(startUpperBound, startLowerBound);
        result.setTimeInfo(stopwatch.getTimeNs());
        result.setMiscInfo(new BigDecimal(rootNode.getNumConfs()));
        */
        return result;
    }

    public BigDecimal getUpperBound(){
        //return values.pstar;
        return state.getUpperBound();
    }

    public BigDecimal getLowerBound(){
        //return values.qstar;
        return state.getLowerBound();
    }

    /**
     * Note: Because nodes are pulled from the queues asynchronously, it is NOT
     * a guarantee that the bounds are correct if any nodes are being processed.
     * Be very careful with when this method is called.
     */
    public void updateStateFromQueues(){
        BigDecimal lastUpper = state.getUpperBound();
        BigDecimal lastLower = state.getLowerBound();
        BigDecimal upperBound = getUpperDirectly();
        BigDecimal lowerBound = getLowerDirectly();
        /*
        BigDecimal upperBound = fringeNodes.getPartitionFunctionUpperBound()
                .add(internalQueue.getPartitionFunctionUpperBound())
                .add(leafQueue.getPartitionFunctionUpperBound())
                .add(finishedNodeZ);
        BigDecimal lowerBound = fringeNodes.getPartitionFunctionLowerBound()
                .add(internalQueue.getPartitionFunctionLowerBound())
                .add(leafQueue.getPartitionFunctionLowerBound())
                .add(finishedNodeZ);

         */

        if(MathTools.isLessThan(lowerBound, lastLower) && ! MathTools.isRelativelySame(lowerBound, lastLower, PartitionFunction.decimalPrecision, 10)) {
            System.err.println("Bounds getting looser. Lower bound is getting lower...");
            errors = true;
        }
        if(MathTools.isGreaterThan(upperBound, lastUpper) && ! MathTools.isRelativelySame(upperBound, lastUpper, PartitionFunction.decimalPrecision, 10)) {
            System.err.println("Bounds getting looser. Upper bound is getting bigger...");
            errors = true;
        }

        state.setBounds(lowerBound, upperBound);
    }

    public void updateBound() {
        //multiSequenceSHARKStarBound.rootNode.computeEpsilonErrorBounds(sequence);
        BigDecimal lastUpper = values.pstar;
        BigDecimal lastLower = values.qstar;
        BigDecimal upperBound = getUpperDirectly();
        BigDecimal lowerBound = getLowerDirectly();

        if(MathTools.isLessThan(lowerBound, lastLower) && ! MathTools.isRelativelySame(lowerBound, lastLower, PartitionFunction.decimalPrecision, 10)) {
            System.err.println("Bounds getting looser. Lower bound is getting lower...");
            errors = true;
        }
        if(MathTools.isGreaterThan(upperBound, lastUpper) && ! MathTools.isRelativelySame(upperBound, lastUpper, PartitionFunction.decimalPrecision, 10)) {
            System.err.println("Bounds getting looser. Upper bound is getting bigger...");
            errors = true;
        }
        values.pstar = upperBound;
        values.qstar = lowerBound;
        values.qprime = upperBound;
        if (upperBound.subtract(lowerBound).compareTo(BigDecimal.ONE) < 1) {
            sequenceEpsilon = 0;
        } else {
            sequenceEpsilon = upperBound.subtract(lowerBound)
                    .divide(upperBound, RoundingMode.HALF_UP).doubleValue();
        }
    }
    List<SeqSpace.ResType> getRTs(SimpleConfSpace.Position confPos, SeqAStarNode.Assignments assignments) {

        // TODO: pre-compute this somehow?
        SeqSpace seqSpace = sequence.seqSpace;

        // map the conf pos to a sequence pos
        SeqSpace.Position seqPos = seqSpace.getPosition(confPos.resNum);
        if (seqPos != null) {

            Integer assignedRT = assignments.getAssignment(seqPos.index);
            if (assignedRT != null) {
                // use just the assigned res type
                return Collections.singletonList(seqPos.resTypes.get(assignedRT));
            } else {
                // use all the res types at the pos
                return seqPos.resTypes;
            }

        } else {

            // immutable position, use all the res types (should just be one)
            assert (confPos.resTypes.size() == 1);

            // use the null value to signal there's no res type here
            return Collections.singletonList(null);
        }
    }

    List<SimpleConfSpace.ResidueConf> getRCs(SimpleConfSpace.Position pos, SeqSpace.ResType rt, SHARKStar.State state) {
        // TODO: pre-compute this somehow?
        if (rt != null) {
            // mutable pos, grab the RCs that match the RT
            return pos.resConfs.stream()
                    .filter(rc -> rc.template.name.equals(rt.name))
                    .collect(Collectors.toList());
        } else {
            // immutable pos, use all the RCs
            return pos.resConfs;
        }
    }

    public boolean nonZeroLower() {
        return state.getLowerBound().compareTo(BigDecimal.ZERO) > 0;
        /*
        return this.fringeNodes.getPartitionFunctionLowerBound().compareTo(BigDecimal.ZERO) > 0
                || internalQueue.getPartitionFunctionLowerBound().compareTo(BigDecimal.ZERO) > 0
                || leafQueue.getPartitionFunctionLowerBound().compareTo(BigDecimal.ZERO) > 0 ;
         */
    }

    @Override
    public void printStats() {
        //multisequenceBound.printEnsembleAnalysis();
        //multisequenceBound.printTimePerSequence();
        if(MultiSequenceSHARKStarBound.debug){
            System.out.printf("State eps: %.9f, [%1.9e, %1.9e], Direct eps: %.9f, [%1.9e, %1.9e]%n",
                    this.state.calcDelta(),
                    this.state.getLowerBound(),
                    this.state.getUpperBound(),
                    getEpsDirectly(),
                    getLowerDirectly(),
                    getUpperDirectly()
            );
        }else{
            System.out.printf("Eps: %.9f, [%1.9e, %1.9e]%n",
                    this.state.calcDelta(),
                    this.state.getLowerBound(),
                    this.state.getUpperBound()
            );
        }
    }
    public boolean errors() {
        return errors;
    }

    public double getSequenceEpsilon() {
        //return sequenceEpsilon;
        return state.calcDelta();
    }

    public BigDecimal getLowerDirectly(){
        return Stream.of(fringeNodes, internalQueue, leafQueue, finishedNodes).
                flatMap(Collection::stream)
                .map((n) -> multisequenceBound.bc.calc(n.getConfUpperBound(sequence)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getUpperDirectly(){
        return Stream.of(fringeNodes, internalQueue, leafQueue, finishedNodes).
                flatMap(Collection::stream)
                .map((n) -> multisequenceBound.bc.calc(n.getConfLowerBound(sequence)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public double getEpsDirectly(){
        BigDecimal upperBound = getUpperDirectly();
        if (MathTools.isZero(upperBound) || MathTools.isInf(upperBound)) {
            return 1.0;
        }else if (upperBound.subtract(getLowerDirectly()).compareTo(BigDecimal.ONE) < 1) {
            return 0.0;
        }
        return new BigMath(PartitionFunction.decimalPrecision)
                .set(upperBound)
                .sub(getLowerBound())
                .div(upperBound)
                .get()
                .doubleValue();
    }

    public BigDecimal getLowerFromQueues(){
        return fringeNodes.getPartitionFunctionLowerBound()
                .add(internalQueue.getPartitionFunctionLowerBound())
                .add(leafQueue.getPartitionFunctionLowerBound())
                .add(finishedNodeZ);
    }

    public BigDecimal getUpperFromQueues(){
        return fringeNodes.getPartitionFunctionUpperBound()
                .add(internalQueue.getPartitionFunctionUpperBound())
                .add(leafQueue.getPartitionFunctionUpperBound())
                .add(finishedNodeZ);
    }

    public double getEpsFromQueues(){
        BigDecimal upperBound = getUpperFromQueues();
        if (MathTools.isZero(upperBound) || MathTools.isInf(upperBound)) {
            return 1.0;
        }else if (upperBound.subtract(getLowerFromQueues()).compareTo(BigDecimal.ONE) < 1) {
            return 0.0;
        }
        return new BigMath(PartitionFunction.decimalPrecision)
                .set(upperBound)
                .sub(getLowerBound())
                .div(upperBound)
                .get()
                .doubleValue();
    }


    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isEmpty() {
        return fringeNodes.isEmpty()
                && internalQueue.isEmpty()
                && leafQueue.isEmpty();
    }

    public static class State{
        private SingleSequenceSHARKStarBound bound; // pointer to the bound

        private BigDecimal upperBound; // pfunc upper bound
        private BigDecimal lowerBound; // pfunc lower bound

        BigDecimal stabilityThreshold;

        private double delta; // running epsilon
        private double targetEpsilon;

        long numEnergiedConfs = 0; // number of conformations fully minimized
        long numExpansions = 0; // number of internal nodes expanded
        long numPartialMinimizations; // number of partially minimized tuples

        double totalTimeEnergy = 0; // total time spent "energy-ing" conformations
        double totalTimeExpansion = 0; // total time spent expanding internal nodes
        double totalTimePartialMin = 0; // total time spent partially minimizing tuples

        long numRoundsEnergy = 0; // number of rounds of full minimization
        long numRoundsExpand = 0; // number of rounds of expansion
        long numRoundsPartialMin = 0; // number of rounds of partial minimization

        AtomicLong numNodesStartedExpanding = new AtomicLong(0);
        AtomicLong numNodesFinishedExpanding = new AtomicLong(0);
        AtomicLong numNodesStartedMinimizing = new AtomicLong(0);
        AtomicLong numNodesFinishedMinimizing = new AtomicLong(0);

        BigDecimal getUpperBound(){
            return upperBound;
        }

        BigDecimal getLowerBound(){
            return lowerBound;
        }

        void setBounds(BigDecimal lower, BigDecimal upper){
            this.lowerBound = lower;
            this.upperBound = upper;
            this.delta = calcDelta();
            updateStatus();
        }

        public void setBoundsWithoutSideEffects(BigDecimal lower, BigDecimal upper){
            this.lowerBound = lower;
            this.upperBound = upper;
        }

        public double getDelta(){
            return delta;
        }

        private double calcDelta() {
            BigDecimal upperBound = getUpperBound();
            if (/*MathTools.isZero(upperBound) ||*/ MathTools.isInf(upperBound)) {
                return 1.0;
            }else if (upperBound.subtract(lowerBound).compareTo(BigDecimal.ONE) < 1) {
                return 0.0;
            }
            return new BigMath(PartitionFunction.decimalPrecision)
                    .set(upperBound)
                    .sub(getLowerBound())
                    .div(upperBound)
                    .get()
                    .doubleValue();
        }

        public long workDone(){
            return numExpansions + numEnergiedConfs;
        }

        public void updateBounds(BigDecimal lowerAddend, BigDecimal upperAddend){
            this.lowerBound = this.lowerBound.add(lowerAddend, PartitionFunction.decimalPrecision);
            this.upperBound = this.upperBound.add(upperAddend, PartitionFunction.decimalPrecision);
            this.delta = calcDelta();
            updateStatus();
        }

        public void updateBoundsWithoutSideEffects(BigDecimal lowerAddend, BigDecimal upperAddend){
            this.lowerBound = this.lowerBound.add(lowerAddend, PartitionFunction.decimalPrecision);
            this.upperBound = this.upperBound.add(upperAddend, PartitionFunction.decimalPrecision);
        }

        private synchronized void updateStatus(){
            if (getDelta() < this.targetEpsilon) {
                this.bound.setStatus(Status.Estimated);
                //TODO: figure out how to fix the none step infinite looping
                if (this.getLowerBound().compareTo(BigDecimal.ZERO) == 0
                ) {
                    this.bound.setStatus(Status.Unstable);
                }
            }else{
                if (!isStable())
                    this.bound.setStatus(Status.Unstable);
            }
        }

        private synchronized boolean isStable(){
            return this.numEnergiedConfs <= 0 ||
                    this.stabilityThreshold == null ||
                    MathTools.isGreaterThanOrEqual(getUpperBound(), this.stabilityThreshold);
        }

        public long getNumExpanding(){
            return numNodesFinishedExpanding.get() - numNodesStartedExpanding.get();
        }

        public long getNumMinimizing(){
            return numNodesFinishedMinimizing.get() - numNodesStartedMinimizing.get();
        }
    }

    public double computeEntropy(double cutoff){
        multisequenceBound.loopTasks.waitForFinish();
        class occEntry{
            double occupancy;
            int numberConfs;

            occEntry(MultiSequenceSHARKStarNode node){
                if(getUpperBound().compareTo(BigDecimal.ZERO) > 0)
                    this.occupancy = multiSequenceSHARKStarBound.bc.calc(node.getConfLowerBound(sequence)).divide(getUpperBound(), PartitionFunction.decimalPrecision).doubleValue();
                else{
                    occupancy = 0;
                }
                this.numberConfs = MultiSequenceSHARKStarNode.computeNumConformations(node, seqRCs).intValue();
            }

            occEntry(double occupancy, int numberConfs){
                this.occupancy = occupancy;
                this.numberConfs = numberConfs;
            }
        }
        List<occEntry> sortedEntries = Stream.of(internalQueue, leafQueue, finishedNodes)
                .flatMap(Collection::stream)
                .parallel()
                .map(occEntry::new)
                .filter((e) -> e.occupancy / e.numberConfs > cutoff) // filter out elements with low occupancy
                .flatMap((e) -> Collections.nCopies(e.numberConfs, new occEntry(e.occupancy / e.numberConfs, 1)).stream()) // expand internal nodes
                .sorted(Comparator.comparingDouble((e) -> -1 * e.occupancy)) // sort by occupancy
                .collect(Collectors.toList());

        // compute the entropy
        return -1 * BoltzmannCalculator.constRT * sortedEntries.parallelStream()
                .map((e) -> e.occupancy* Math.log(e.occupancy))
                .reduce(0.0, Double::sum);
    }

    public long getNumConfsMinimized(){
        return state.numEnergiedConfs;
    }

    public double getLargestCorrection(){
        OptionalDouble max = multisequenceBound.correctionMatrix.getAllCorrections().parallelStream().mapToDouble(t -> t.E).max();
        if (max.isPresent())
            return max.getAsDouble();
        else
            return 0.0;
    }

    public int getMaxCorrectionImpact(){
        return multisequenceBound.correctionMatrix.getNumAffectedSequences(multisequenceBound.confSpace.seqSpace)
                .stream()
                .reduce(0, (a,b) -> { // an implementation of max
                    if (a>b)
                        return a;
                    else
                        return b;
                });
    }

    public double getAverageCorrectionImpact(){
        List<Integer> affected = multisequenceBound.correctionMatrix.getNumAffectedSequences(multisequenceBound.confSpace.seqSpace);
        return affected.stream()
                .reduce(0, (a,b) -> { // an implementation of max
                    if (a>b)
                        return a;
                    else
                        return b;
                }).doubleValue() / affected.size();
    }
}
