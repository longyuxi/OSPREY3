package edu.duke.cs.osprey.sharkstar;

import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.pruning.AStarPruner;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.astar.conf.scoring.PairwiseGScorer;
import edu.duke.cs.osprey.astar.conf.scoring.TraditionalPairwiseHScorer;
import edu.duke.cs.osprey.astar.conf.scoring.mplp.EdgeUpdater;
import edu.duke.cs.osprey.astar.conf.scoring.mplp.MPLPUpdater;
import edu.duke.cs.osprey.confspace.*;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.ematrix.NegatedEnergyMatrix;
import edu.duke.cs.osprey.ematrix.UpdatingEnergyMatrix;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.energy.ResidueForcefieldBreakdown;
import edu.duke.cs.osprey.gmec.ConfAnalyzer;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.markstar.MARKStarProgress;
import edu.duke.cs.osprey.markstar.framework.StaticBiggestLowerboundDifferenceOrder;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.sharkstar.SHARKStarNode.Node;
import edu.duke.cs.osprey.parallelism.Parallelism;
import edu.duke.cs.osprey.parallelism.TaskExecutor;
import edu.duke.cs.osprey.tools.MathTools;
import edu.duke.cs.osprey.tools.ObjectPool;
import edu.duke.cs.osprey.tools.Stopwatch;
import jdk.nashorn.internal.runtime.regexp.joni.exception.ValueException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.*;

import static org.apache.commons.lang3.ArrayUtils.add;

import java.util.stream.Collectors;

public class SHARKStarBound implements PartitionFunction {
    public int TestNumCorrections=0;

	protected double targetEpsilon = 1;
	public boolean debug = true;
	public boolean profileOutput = false;
	private Status status = null;
	private Values values = null;

	// the number of full conformations minimized
	private int numConfsEnergied = 0;
	// max confs minimized, -1 means infinite.
	private int maxNumConfs = -1;

	protected int maxMinimizations = 1;


	// the number of full conformations scored OR energied
	private int numConfsScored = 0;

	protected int numInternalNodesProcessed = 0;

	private boolean printMinimizedConfs;
	private MARKStarProgress progress;
	public String stateName = String.format("%4f",Math.random());
	private int numPartialMinimizations;
	public ArrayList<Integer> minList;
	protected double internalTimeAverage;
	protected double leafTimeAverage;
	private double cleanupTime;
	private boolean nonZeroLower;
	protected static TaskExecutor loopTasks;


	// We keep track of the root node for computing our K* bounds
	public SHARKStarNode rootNode;
	// Heap of nodes for recursive expansion
	protected final Queue<SHARKStarNode> queue;
	protected double epsilonBound = Double.POSITIVE_INFINITY;
	private ConfIndex confIndex;
	public StaticBiggestLowerboundDifferenceOrder order;
	// TODO: Implement new AStarPruner for MARK*?
	public final AStarPruner pruner;
	protected RCs RCs;
	protected Parallelism parallelism;
	private ObjectPool<ScoreContext> contexts;
	private SHARKStarNode.ScorerFactory gscorerFactory;
	private SHARKStarNode.ScorerFactory hscorerFactory;

	public boolean reduceMinimizations = true;
	private ConfAnalyzer confAnalyzer;
	EnergyMatrix minimizingEmat;
	EnergyMatrix rigidEmat;
	UpdatingEnergyMatrix correctionMatrix;
	ConfEnergyCalculator minimizingEcalc;
	private Stopwatch stopwatch = new Stopwatch().start();
	// Variables for reporting pfunc reductions more accurately
	BigDecimal startUpperBound = null; //can't start with infinity
	BigDecimal startLowerBound = BigDecimal.ZERO;
	BigDecimal lowerReduction_FullMin = BigDecimal.ZERO; //Pfunc lower bound improvement from full minimization
	BigDecimal lowerReduction_ConfUpperBound = BigDecimal.ZERO; //Pfunc lower bound improvement from conf upper bounds
	BigDecimal upperReduction_FullMin = BigDecimal.ZERO; //Pfunc upper bound improvement from full minimization
	BigDecimal upperReduction_PartialMin = BigDecimal.ZERO; //Pfunc upper bound improvement from partial minimization corrections
	BigDecimal upperReduction_ConfLowerBound = BigDecimal.ZERO; //Pfunc upper bound improvement from conf lower bounds

	BigDecimal cumulativeZCorrection = BigDecimal.ZERO;//Pfunc upper bound improvement from partial minimization corrections
	BigDecimal ZReductionFromMin = BigDecimal.ZERO;//Pfunc lower bound improvement from full minimization
	BoltzmannCalculator bc = new BoltzmannCalculator(PartitionFunction.decimalPrecision);
	private boolean computedCorrections = false;
	private long loopPartialTime = 0;
	private Set<String> correctedTuples = Collections.synchronizedSet(new HashSet<>());
	private BigDecimal stabilityThreshold;
	private double leafTimeSum = 0;
	private double internalTimeSum = 0;
	private int numLeavesScored = 0;
	private int numInternalScored = 0;

	private SHARKStarBound precomputedPfunc;
	public SHARKStarNode precomputedRootNode;
	private SimpleConfSpace confSpace;
	private int[] confSpacePermutation;

	private BigDecimal precomputedUpperBound;
	private BigDecimal precomputedLowerBound;

	private Queue<SHARKStarNode> leafQueue;
	private Queue<SHARKStarNode> internalQueue;
	private SHARKStarNodeScorer sharkStarNodeScorer;

	private Map<Sequence, SHARKStarQueue> sequenceQueues = new HashMap<>();

	/**
	 * Constructor to make a default SHARKStarBound Class
	 * @param confSpace the partition function conformation space
	 * @param rigidEmat the rigid pairwise energy matrix
	 * @param minimizingEmat the parwise-minimized energy matrix
	 * @param minimizingConfEcalc the energy calculator to calculate minimized conf energies
	 * @param rcs information on possible rotamers at all design positions
	 * @param parallelism information for threading
	 */
	public SHARKStarBound(SimpleConfSpace confSpace, EnergyMatrix rigidEmat, EnergyMatrix minimizingEmat,
						 ConfEnergyCalculator minimizingConfEcalc, RCs rcs, Parallelism parallelism) {
		this.queue = new PriorityQueue<>();
		this.minimizingEcalc = minimizingConfEcalc;
		gscorerFactory = (emats) -> new PairwiseGScorer(emats);

		MPLPUpdater updater = new EdgeUpdater();
		hscorerFactory = (emats) -> new TraditionalPairwiseHScorer(emats, rcs);//MPLPPairwiseHScorer(updater, emats, 1, 0.0001);//

		rootNode = SHARKStarNode.makeRoot(confSpace, rigidEmat, minimizingEmat, rcs,
				gscorerFactory.make(minimizingEmat), hscorerFactory.make(minimizingEmat),
				gscorerFactory.make(rigidEmat),
				new TraditionalPairwiseHScorer(new NegatedEnergyMatrix(confSpace, rigidEmat), rcs), true);
		//hscorerFactory.make(new NegatedEnergyMatrix(confSpace, rigidEmat), rcs), true);
		confIndex = new ConfIndex(rcs.getNumPos());
		this.minimizingEmat = minimizingEmat;
		this.rigidEmat = rigidEmat;
		this.RCs = rcs;
		this.order = new StaticBiggestLowerboundDifferenceOrder();
		order.setScorers(gscorerFactory.make(minimizingEmat),hscorerFactory.make(minimizingEmat));
		this.pruner = null;

		this.contexts = new ObjectPool<>((lingored) -> {
			ScoreContext context = new ScoreContext();
			context.index = new ConfIndex(rcs.getNumPos());
			context.gscorer = gscorerFactory.make(minimizingEmat);
			context.hscorer = hscorerFactory.make(minimizingEmat);
			context.rigidscorer = gscorerFactory.make(rigidEmat);
			/** These scoreres should match the scorers in the SHARKStarNode root - they perform the same calculations**/
			context.negatedhscorer = new TraditionalPairwiseHScorer(new NegatedEnergyMatrix(confSpace, rigidEmat), rcs); //this is used for upper bounds, so we want it rigid
			context.ecalc = minimizingConfEcalc;
			return context;
		});

		this.leafQueue = new PriorityQueue<>();
		this.internalQueue = new PriorityQueue<>();

		correctionMatrix = new UpdatingEnergyMatrix(confSpace, minimizingEmat, minimizingConfEcalc);

		progress = new MARKStarProgress(RCs.getNumPos());
		//confAnalyzer = new ConfAnalyzer(minimizingConfEcalc, minimizingEmat);
		confAnalyzer = new ConfAnalyzer(minimizingConfEcalc);
		setParallelism(parallelism);
		updateBound();

		// Recording pfunc starting bounds
		this.startLowerBound = rootNode.getLowerBound();
		this.startUpperBound = rootNode.getUpperBound();
		this.minList = new ArrayList<Integer>(Collections.nCopies(rcs.getNumPos(),0));
		this.confSpace = confSpace;
		this.confSpacePermutation = null;
    }

	public SHARKStarBound(SimpleConfSpace confSpace, EnergyMatrix rigidEmat, EnergyMatrix minimizingEmat,
						  ConfEnergyCalculator minimizingConfEcalc, RCs rcs, Parallelism parallelism,
						  SHARKStarBound precomputedFlex) {

		this(confSpace, rigidEmat, minimizingEmat, minimizingConfEcalc, rcs, parallelism);
		/*
		For now, let's assume we are working with a single sequence
		 */
		this.precomputedPfunc = precomputedFlex;
		this.precomputedRootNode = precomputedFlex.rootNode;
		this.precomputedUpperBound = precomputedRootNode.getUpperBound();
		this.precomputedLowerBound = precomputedRootNode.getLowerBound();
		this.confSpacePermutation = genConfSpaceMapping();

		updatePrecomputedConfTree();
		addPrecomputedFringeToQueue();

		// Fix order issues
		ConfIndex rootIndex = new ConfIndex(RCs.getNumPos());
        this.rootNode.getConfSearchNode().index(rootIndex);
		this.order.updateForPrecomputedOrder(precomputedFlex.order, rootIndex, this.RCs, genConfSpaceMapping());

		updateBound();

		/*
		TODO: Make sure all of the energy matrices (including the correction emats) are compatible with the new confspace
		 */
	}

	/**
	 * Returns the partition function lower bound for a particular sequence
	 *
	 * Note that SHARKStarBound will eventually contain a multi-sequence confTree, although this isn't currently the case
	 *
	 * @param seq Sequence for which to get pfunc lower bound
	 * @return BigDecimal pfunc lower bound
	 */
	public BigDecimal getLowerBound (Sequence seq){
		throw new UnsupportedOperationException("getLowerBound(seq) is not yet implemented");
	}

	/**
	 * Returns the partition function upper bound for a particular sequence
	 *
	 * Note that SHARKStarBound will eventually contain a multi-sequence confTree, although this isn't currently the case
	 *
	 * @param seq Sequence for which to get pfunc upper bound
	 * @return BigDecimal pfunc upper bound
	 */
	public BigDecimal getUpperBound (Sequence seq){
		throw new UnsupportedOperationException("getUpperBound(seq) is not yet implemented");
	}

	/**
	 * Returns the partition function lower bound for the whole confTree
	 *
	 * Note that SHARKStarBound will eventually contain a multi-sequence confTree, although this isn't currently the case
	 *
	 * @return BigDecimal pfunc lower bound
	 */
	public BigDecimal getLowerBound (){
		return rootNode.getLowerBound();
	}

	/**
	 * Returns the partition function upper bound for the whole confTree
	 *
	 * Note that SHARKStarBound will eventually contain a multi-sequence confTree, although this isn't currently the case
	 *
	 * @return BigDecimal pfunc upper bound
	 */
	public BigDecimal getUpperBound (){
		return rootNode.getUpperBound();
	}

	/**
	 * Returns the partition function lower bound for the precomputed confspace
	 *
	 * @return BigDecimal precomputed pfunc lower bound
	 */
	public BigDecimal getPrecomputedLowerBound (){
		return precomputedLowerBound;
	}

	/**
	 * Returns the partition function upper bound for the precomputed confTree
	 *
	 * @return BigDecimal precomputed pfunc upper bound
	 */
	public BigDecimal getPrecomputedUpperBound (){
		return precomputedUpperBound;
	}

	/**
	 * Makes the current confTree consistent with the current confSpace
	 *
	 * When we precompute flexible residues, we will have a tree that is for a flexible confspace.
	 * However, when we want to compute for mutable residues, we need to extend the length of assignments in our tree
	 */
	public void updatePrecomputedConfTree(){
		updatePrecomputedNode(precomputedRootNode, this.confSpace.getNumPos());
		this.rootNode = precomputedRootNode;
		/*
		any full conformations should be minimized and should be added to the MAE energy matrix
		likewise for any partial minimizations
		 */

		//TODO: update partial minimizations and full conformations?
	}

	/**
	 * Recursively makes the subtree rooted by node compatible with the new confspace
	 *
	 * @param node the subtree root
	 * @param size the number of positions in the new confspace
	 */
	private void updatePrecomputedNode(SHARKStarNode node, int size){
		if (node.getChildren() != null){
            for (SHARKStarNode child : node.getChildren()){
                updatePrecomputedNode(child, size);
            }
		}
		node.makeNodeCompatibleWithConfSpace(confSpacePermutation, size, this.RCs);
	}

	/**
	 * Add the newly updated nodes to the queue so that we don't redo any work
	 */
	private void addPrecomputedFringeToQueue(){
		addSubTreeFringeToQueue(this.rootNode);

	}

	/**
	 * Add the fringe nodes in the current subtree to the queue
	 * @param subTreeRoot the root node of the subtree on which to operate
	 */
	private void addSubTreeFringeToQueue(SHARKStarNode subTreeRoot){
		if (subTreeRoot.isLeaf()) {
		    // Compute correct hscores
			try(ObjectPool.Checkout<ScoreContext> checkout = contexts.autoCheckout()) {
				ScoreContext context = checkout.get();
				// index the node
				subTreeRoot.getConfSearchNode().index(context.index);
				double hscore = context.hscorer.calc(context.index, RCs);
				double maxhscore = -context.negatedhscorer.calc(context.index, RCs);
				Node confNode = subTreeRoot.getConfSearchNode();
				double confLowerBound = confNode.gscore + hscore;
				double confUpperBound = confNode.rigidScore + maxhscore;
				confNode.setConfLowerBound(confLowerBound);
				confNode.setConfUpperBound(confUpperBound);
			}
			// add node to queue
			queue.add(subTreeRoot);
		}else{
			for (SHARKStarNode node : subTreeRoot.getChildren()){
			addSubTreeFringeToQueue(node);
			}
		}
	}

	/**
	 * Generate a permutation matrix that lets us map positions from the precomputed confspace to the new confspace
	 */
	public int[] genConfSpaceMapping(){
	    // the permutation matrix maps confs in the precomputed flexible to the full confspace
        // Note that I think this works because Positions have equals() check the residue number
		return precomputedPfunc.confSpace.positions.stream()
				.mapToInt(confSpace.positions :: indexOf)
				.toArray();
	}

	public int[] getConfSpacePermutation(){
		return this.confSpacePermutation;
	}

	/**
	 * Returns a correction matrix with full minimizations included
	 */
	public UpdatingEnergyMatrix genCorrectionMatrix(){
		addFullMinimizationsToCorrectionMatrix();
		return this.correctionMatrix;
	}

	/**
	 * Takes partial minimizations from the precomputed correctionMatrix, maps them to the new confspace, and
	 * stores them in this correctionMatrix
	 */
	public void mergeCorrections(UpdatingEnergyMatrix precomputedCorrections){
	    List<TupE> corrections = precomputedCorrections.getAllCorrections().stream()
				.map((tup) -> {
					return tup.permute(confSpacePermutation);
				})
				.collect(Collectors.toList());
	    if (corrections.size()!=0) {
			TestNumCorrections = corrections.size();
			this.correctionMatrix.insertAll(corrections);
		}else
	    	System.out.println("No corrections to insert");
	}

	/**
	 * Takes the full minimizations from this tree, insert them into the correction matrix
	 */
	private void addFullMinimizationsToCorrectionMatrix(){
		captureSubtreeFullMinimizations(this.rootNode);
	}

	/**
	 * Takes the full minimizations from this subtree, insert them into the correction matrix
	 */
	private void captureSubtreeFullMinimizations(SHARKStarNode subTreeRoot){
		if (subTreeRoot.getChildren() == null || subTreeRoot.getChildren().size() ==0){
			if (subTreeRoot.getConfSearchNode().isMinimized()){
				RCTuple tuple = subTreeRoot.toTuple();
				double confEnergy = subTreeRoot.getConfSearchNode().getConfLowerBound();
				double lowerbound = this.minimizingEmat.getInternalEnergy(tuple);
				if (lowerbound == confEnergy)
					throw new ValueException("Minimized energies shouldn't equal lower bounds");
				double correction = confEnergy - lowerbound;
				this.correctionMatrix.setHigherOrder(tuple, correction);
			}
		}else{
			for (SHARKStarNode node : subTreeRoot.getChildren()){
				captureSubtreeFullMinimizations(node);
			}
		}

	}

	private class SHARKStarQueue extends PriorityQueue<SHARKStarNode> {
		private BigDecimal partitionFunctionUpperSum = BigDecimal.ONE;
		private BigDecimal partitionFunctionLowerSum= BigDecimal.ONE;

		public BigDecimal getPartitionFunctionUpperBound() {
			return partitionFunctionUpperSum;
		}

		public BigDecimal getPartitionFunctionLowerBound() {
			return partitionFunctionLowerSum;
		}

		public boolean add(SHARKStarNode node) {
			partitionFunctionUpperSum = partitionFunctionUpperSum.add(node.getUpperBound());
			partitionFunctionLowerSum = partitionFunctionLowerSum.add(node.getLowerBound());
			return super.add(node);
		}

		public boolean poll(SHARKStarNode node) {
			partitionFunctionUpperSum = partitionFunctionUpperSum.subtract(node.getUpperBound());
			partitionFunctionLowerSum = partitionFunctionLowerSum.subtract(node.getLowerBound());
			return super.add(node);
		}
	}
	@Override
	public void init(ConfSearch confSearch, BigInteger numConfsBeforePruning, double targetEpsilon){
		init(targetEpsilon);
	}

	public void setRCs(RCs rcs) {
		RCs = rcs;
	}

	public void setReportProgress(boolean showPfuncProgress) {
		this.printMinimizedConfs = true;
	}

	@Override
	public void setConfListener(ConfListener val) {

	}

	@Override
	public void setStabilityThreshold(BigDecimal threshold) {
		stabilityThreshold = threshold;
	}

	public void setMaxNumConfs(int maxNumConfs) {
		this.maxNumConfs = maxNumConfs;
	}

	public void init(double targetEpsilon) {
		this.targetEpsilon = targetEpsilon;
		status = Status.Estimating;
		values = new Values();
	}

	public void init(double epsilon, BigDecimal stabilityThreshold) {
		targetEpsilon = epsilon;
		status = Status.Estimating;
		values = new Values();
		this.stabilityThreshold = stabilityThreshold;
	}


	@Override
	public Status getStatus() {
		return status;
	}

	@Override
	public PartitionFunction.Values getValues() {
		return values;
	}

	@Override
	public int getParallelism() {
		return 0;
	}

	@Override
	public int getNumConfsEvaluated() {
		return numConfsEnergied;
	}

	public int getNumConfsScored() {
		return numConfsScored;
	}

	private int workDone() {
		return numInternalNodesProcessed + numConfsEnergied + numConfsScored + numPartialMinimizations ;
	}

	@Override
	public void compute(int maxNumConfs) {
		debugPrint("Num conformations: "+rootNode.getConfSearchNode().getNumConformations());
		double lastEps = 1;

		int previousConfCount = workDone();

		if(!nonZeroLower) {
			runUntilNonZero();
			updateBound();
		}

		while (epsilonBound > targetEpsilon &&
				workDone()-previousConfCount < maxNumConfs
				&& isStable(stabilityThreshold)) {
			debugPrint("Tightening from epsilon of "+epsilonBound);
			if(debug) {
				debugHeap(queue);
				//rootNode.printTree();
			}
			tightenBoundInPhases();
			debugPrint("Errorbound is now "+epsilonBound);
			if(lastEps < epsilonBound && epsilonBound - lastEps > 0.01) {
				System.err.println("Error. Bounds got looser.");
				//System.exit(-1);
			}
			lastEps = epsilonBound;
		}
		if(!isStable(stabilityThreshold))
			status = Status.Unstable;
		loopTasks.waitForFinish();
		minimizingEcalc.tasks.waitForFinish();
		BigDecimal averageReduction = BigDecimal.ZERO;
		int totalMinimizations = numConfsEnergied + numPartialMinimizations;
		if(totalMinimizations> 0)
			averageReduction = cumulativeZCorrection
					.divide(new BigDecimal(totalMinimizations), new MathContext(BigDecimal.ROUND_HALF_UP));
		debugPrint(String.format("Average Z reduction per minimization: %12.6e",averageReduction));
		values.pstar = rootNode.getUpperBound();
		values.qstar = rootNode.getLowerBound();
		values.qprime= rootNode.getUpperBound();
		if(epsilonBound < targetEpsilon) {
			status = Status.Estimated;
			if(values.qstar.compareTo(BigDecimal.ZERO) == 0) {
				status = Status.Unstable;
			}
			//rootNode.printTree();//stateName, minimizingEcalc.confSpace);
		}
	}

	protected void debugPrint(String s) {
		if(debug)
			System.out.println(s);
	}

	protected void profilePrint(String s) {
		if(profileOutput)
			System.out.println(s);
	}

	public void compute() {
		compute(Integer.MAX_VALUE);
	}

	@Override
	public Result makeResult() {
		// Calculate the upper bound z reductions from conf lower bounds, since we don't explicitly record these
		lowerReduction_ConfUpperBound = rootNode.getLowerBound().subtract(startLowerBound).subtract(lowerReduction_FullMin);
		// Calculate the lower bound z reductions from conf upper bounds, since we don't explicitly record these
		upperReduction_ConfLowerBound = startUpperBound.subtract(rootNode.getUpperBound()).subtract(upperReduction_FullMin).subtract(upperReduction_PartialMin);

		PartitionFunction.Result result = new PartitionFunction.Result(getStatus(), getValues(), getNumConfsEvaluated());
        /*
        result.setWorkInfo(numPartialMinimizations, numConfsScored,minList);
        result.setZInfo(lowerReduction_FullMin, lowerReduction_ConfUpperBound, upperReduction_FullMin, upperReduction_PartialMin, upperReduction_ConfLowerBound);
        result.setOrigBounds(startUpperBound, startLowerBound);
        result.setTimeInfo(stopwatch.getTimeNs());
        result.setMiscInfo(new BigDecimal(rootNode.getNumConfs()));
        */
		return result;
	}

	public void setParallelism(Parallelism val) {

		if (val == null) {
			val = Parallelism.makeCpu(1);
		}

		parallelism = val;
		//loopTasks = minimizingEcalc.tasks;
		if(loopTasks == null)
			loopTasks = parallelism.makeTaskExecutor(1000);
		contexts.allocate(parallelism.getParallelism());
	}

	private void debugEpsilon(double curEpsilon) {
		if(debug && curEpsilon < epsilonBound) {
			System.err.println("Epsilon just got bigger.");
		}
	}

	protected boolean shouldMinimize(Node node) {
		return node.getLevel() == RCs.getNumPos() && !node.isMinimized();
	}

	protected void recordCorrection(double lowerBound, double correction) {
		BigDecimal upper = bc.calc(lowerBound);
		BigDecimal corrected = bc.calc(lowerBound + correction);
		cumulativeZCorrection = cumulativeZCorrection.add(upper.subtract(corrected));
		upperReduction_PartialMin = upperReduction_PartialMin.add(upper.subtract(corrected));
	}
	private void recordReduction(double lowerBound, double upperBound, double energy) {
		BigDecimal lowerBoundWeight = bc.calc(lowerBound);
		BigDecimal upperBoundWeight = bc.calc(upperBound);
		BigDecimal energyWeight = bc.calc(energy);
		ZReductionFromMin = ZReductionFromMin.add(lowerBoundWeight.subtract(upperBoundWeight));
		upperReduction_FullMin = upperReduction_FullMin.add(lowerBoundWeight.subtract(energyWeight));
		lowerReduction_FullMin = lowerReduction_FullMin.add(energyWeight.subtract(upperBoundWeight));

	}

	private void debugBreakOnConf(int[] conf) {
		int[] confOfInterest = new int[]{4,5,8,18};
		if(conf.length != confOfInterest.length)
			return;
		boolean match = true;
		for(int i = 0; i < confOfInterest.length; i++) {
			if(conf[i] != confOfInterest[i]) {
				match = false;
				break;
			}
		}
		if(match)
			System.out.println("Matched "+SimpleConfSpace.formatConfRCs(conf));
	}

	// We want to process internal nodes without worrying about the bound too much until we have
	// a nonzero lower bound. We have to have a nonzero lower bound, so we have to have at least
	// one node with a negative conf upper bound.
	private void runUntilNonZero() {
		System.out.println("Running until leaf is found...");
		double bestConfUpper = Double.POSITIVE_INFINITY;

		List<SHARKStarNode> newNodes = new ArrayList<>();
		List<SHARKStarNode> leafNodes = new ArrayList<>();
		int numNodes = 0;
		Stopwatch leafLoop = new Stopwatch().start();
		Stopwatch overallLoop = new Stopwatch().start();
		if (queue.isEmpty())
		    queue.add(rootNode);
        boundLowestBoundConfUnderNode(queue.poll(), newNodes);
		queue.addAll(newNodes);


		newNodes.clear();
		System.out.println("Found a leaf!");
		nonZeroLower = true;
	}

	protected void tightenBoundInPhases() {
		System.out.println(String.format("Current overall error bound: %12.10f, spread of [%12.6e, %12.6e]",epsilonBound, rootNode.getLowerBound(), rootNode.getUpperBound()));
		List<SHARKStarNode> internalNodes = new ArrayList<>();
		List<SHARKStarNode> leafNodes = new ArrayList<>();
		List<SHARKStarNode> newNodes = Collections.synchronizedList(new ArrayList<>());
		BigDecimal internalZ = BigDecimal.ONE;
		BigDecimal leafZ = BigDecimal.ONE;
		int numNodes = 0;
		Stopwatch loopWatch = new Stopwatch();
		loopWatch.start();
		Stopwatch internalTime = new Stopwatch();
		Stopwatch leafTime = new Stopwatch();
		double leafTimeSum = 0;
		double internalTimeSum = 0;
		BigDecimal[] ZSums = new BigDecimal[]{internalZ,leafZ};
		populateQueues(queue, internalNodes, leafNodes, internalZ, leafZ, ZSums);
		updateBound();
		debugPrint(String.format("After corrections, bounds are now [%12.6e,%12.6e]",rootNode.getLowerBound(),rootNode.getUpperBound()));
		internalZ = ZSums[0];
		leafZ = ZSums[1];
		System.out.println(String.format("Z Comparison: %12.6e, %12.6e", internalZ, leafZ));
		if(MathTools.isLessThan(internalZ, leafZ)) {
			numNodes = leafNodes.size();
			System.out.println("Processing "+numNodes+" leaf nodes...");
			leafTime.reset();
			leafTime.start();
			for(SHARKStarNode leafNode: leafNodes) {
				processFullConfNode(newNodes, leafNode, leafNode.getConfSearchNode());
				leafNode.markUpdated();
				debugPrint("Processing Node: " + leafNode.getConfSearchNode().toString());
			}
			loopTasks.waitForFinish();
			leafTime.stop();
			leafTimeAverage = leafTime.getTimeS();
			System.out.println("Processed "+numNodes+" leaves in "+leafTimeAverage+" seconds.");
			if(maxMinimizations < parallelism.numThreads)
				maxMinimizations++;
			internalQueue.addAll(internalNodes);
		}
		else {
			numNodes = internalNodes.size();
			System.out.println("Processing "+numNodes+" internal nodes...");
			internalTime.reset();
			internalTime.start();
			for (SHARKStarNode internalNode : internalNodes) {
				if(!MathTools.isGreaterThan(internalNode.getLowerBound(),BigDecimal.ONE) &&
						MathTools.isGreaterThan(
								MathTools.bigDivide(internalNode.getUpperBound(),rootNode.getUpperBound(),
										PartitionFunction.decimalPrecision),
								new BigDecimal(1-targetEpsilon))
				) {
					loopTasks.submit(() -> {
						boundLowestBoundConfUnderNode(internalNode, newNodes);
						return null;
					}, (ignored) -> {
					});
				}
				else {
					processPartialConfNode(newNodes, internalNode, internalNode.getConfSearchNode());
				}
				internalNode.markUpdated();
			}
			loopTasks.waitForFinish();
			internalTime.stop();
			internalTimeSum=internalTime.getTimeS();
			internalTimeAverage = internalTimeSum/Math.max(1,internalNodes.size());
			debugPrint("Internal node time :"+internalTimeSum+", average "+internalTimeAverage);
			numInternalNodesProcessed+=internalNodes.size();
			leafQueue.addAll(leafNodes);
		}
		if (epsilonBound <= targetEpsilon)
			return;
		loopCleanup(newNodes, loopWatch, numNodes);
	}

	protected void debugHeap(Queue<SHARKStarNode> queue) {
		int maxNodes = 10;
		System.out.println("Node heap:");
		List<SHARKStarNode> nodes = new ArrayList<>();
		while(!queue.isEmpty() && nodes.size() < 10)
		{
			SHARKStarNode node = queue.poll();
			System.out.println(node.getConfSearchNode());
			nodes.add(node);
		}
		queue.addAll(nodes);
	}


	boolean isStable(BigDecimal stabilityThreshold) {
		return numConfsEnergied <= 0 || stabilityThreshold == null
				|| MathTools.isGreaterThanOrEqual(rootNode.getUpperBound(), stabilityThreshold);
	}


	protected void populateQueues(Queue<SHARKStarNode> queue, List<SHARKStarNode> internalNodes, List<SHARKStarNode> leafNodes, BigDecimal internalZ,
								  BigDecimal leafZ, BigDecimal[] ZSums) {
		List<SHARKStarNode> leftoverLeaves = new ArrayList<>();
		//int maxNodes = 1000;
		int maxNodes = 1;
		if(leafTimeAverage > 0)
			maxNodes = Math.max(maxNodes, (int)Math.floor(0.1*leafTimeAverage/internalTimeAverage));
		while(!queue.isEmpty() && (internalQueue.size() < maxNodes || leafQueue.size() < maxMinimizations)){
			SHARKStarNode curNode = queue.poll();
			Node node = curNode.getConfSearchNode();
			ConfIndex index = new ConfIndex(RCs.getNumPos());
			node.index(index);
			double correctgscore = correctionMatrix.confE(node.assignments);
			double hscore = node.getConfLowerBound() - node.gscore;
			double confCorrection = Math.min(correctgscore, node.rigidScore) + hscore;
			if(!node.isMinimized() && node.getConfLowerBound() < confCorrection
					&& node.getConfLowerBound() - confCorrection > 1e-5) {
				if(confCorrection < node.getConfLowerBound()) {
					System.out.println("huh!?");
				}
				System.out.println("Correction from "+correctionMatrix.sourceECalc+":"+node.gscore+"->"+correctgscore);
				recordCorrection(node.getConfLowerBound(), correctgscore - node.gscore);

				node.gscore = correctgscore;
				if (confCorrection > node.rigidScore) {
					System.out.println("Overcorrected"+SimpleConfSpace.formatConfRCs(node.assignments)+": " + confCorrection + " > " + node.rigidScore);
					node.gscore = node.rigidScore;
					confCorrection = node.rigidScore + hscore;
				}
				node.setBoundsFromConfLowerAndUpper(confCorrection, node.getConfUpperBound());
				curNode.markUpdated();
				leftoverLeaves.add(curNode);
				continue;
			}


			if (node.getLevel() < RCs.getNumPos()) {
				internalQueue.add(curNode);
			}
			else if(shouldMinimize(node) && !correctedNode(leftoverLeaves, curNode, node)) {
				leafQueue.add(curNode);
			}

		}

		ZSums[0] = fillListFromQueue(internalNodes, internalQueue, maxNodes);
		ZSums[1] = fillListFromQueue(leafNodes, leafQueue, maxMinimizations);
		queue.addAll(leftoverLeaves);
	}

	private BigDecimal fillListFromQueue(List<SHARKStarNode> list, Queue<SHARKStarNode> queue, int max) {
		BigDecimal sum = BigDecimal.ZERO;
		List<SHARKStarNode> leftovers = new ArrayList<>();
		while(!queue.isEmpty() && list.size() < max) {
			SHARKStarNode curNode = queue.poll();
			if(correctedNode(leftovers, curNode, curNode.getConfSearchNode())) {
				continue;
			}
			BigDecimal diff = curNode.getUpperBound().subtract(curNode.getLowerBound());
			sum = sum.add(diff);
			list.add(curNode);
		}
		queue.addAll(leftovers);
		return sum;
	}

	protected void loopCleanup(List<SHARKStarNode> newNodes, Stopwatch loopWatch, int numNodes) {
		for(SHARKStarNode node: newNodes) {
			if(node != null)
				queue.add(node);
		}
		loopWatch.stop();
		double loopTime = loopWatch.getTimeS();
		profilePrint("Processed "+numNodes+" this loop, spawning "+newNodes.size()+" in "+loopTime+", "+stopwatch.getTime()+" so far");
		loopWatch.reset();
		loopWatch.start();
		processPreminimization(minimizingEcalc);
		profilePrint("Preminimization time : "+loopWatch.getTime(2));
		double curEpsilon = epsilonBound;
		//rootNode.updateConfBounds(new ConfIndex(RCs.getNumPos()), RCs, gscorer, hscorer);
		updateBound();
		loopWatch.stop();
		cleanupTime = loopWatch.getTimeS();
		//double scoreChange = rootNode.updateAndReportConfBoundChange(new ConfIndex(RCs.getNumPos()), RCs, correctiongscorer, correctionhscorer);
		System.out.println(String.format("Loop complete. Bounds are now [%12.6e,%12.6e]",rootNode.getLowerBound(),rootNode.getUpperBound()));
	}

	protected boolean correctedNode(List<SHARKStarNode> newNodes, SHARKStarNode curNode, Node node) {
		assert(curNode != null && node != null);
		double confCorrection = correctionMatrix.confE(node.assignments);
		if((node.getLevel() == RCs.getNumPos() && node.getConfLowerBound()< confCorrection)
				|| node.gscore < confCorrection) {
			double oldg = node.gscore;
			node.gscore = confCorrection;
			recordCorrection(oldg, confCorrection - oldg);
			node.setBoundsFromConfLowerAndUpper(node.getConfLowerBound() - oldg + confCorrection, node.getConfUpperBound());
			curNode.markUpdated();
			newNodes.add(curNode);
			return true;
		}
		return false;
	}

	private SHARKStarNode drillDown(List<SHARKStarNode> newNodes, SHARKStarNode curNode, Node node) {
		try(ObjectPool.Checkout<ScoreContext> checkout = contexts.autoCheckout()) {
			ScoreContext context = checkout.get();
			ConfIndex confIndex = context.index;
			node.index(confIndex);
			// which pos to expand next?
			int nextPos = order.getNextPos(confIndex, RCs);
			assert (!confIndex.isDefined(nextPos));
			assert (confIndex.isUndefined(nextPos));

			// score child nodes with tasks (possibly in parallel)
			List<SHARKStarNode> children = new ArrayList<>();
			double bestChildLower = Double.POSITIVE_INFINITY;
			SHARKStarNode bestChild = null;
			for (int nextRc : RCs.get(nextPos)) {

				if (hasPrunedPair(confIndex, nextPos, nextRc)) {
					continue;
				}

				// if this child was pruned dynamically, then don't score it
				if (pruner != null && pruner.isPruned(node, nextPos, nextRc)) {
					continue;
				}
				Stopwatch partialTime = new Stopwatch().start();
				Node child = node.assign(nextPos, nextRc);
				double confLowerBound = Double.POSITIVE_INFINITY;

				// score the child node differentially against the parent node
				if (child.getLevel() < RCs.getNumPos()) {
					double confCorrection = correctionMatrix.confE(child.assignments);
					double diff = confCorrection;
					double rigiddiff = context.rigidscorer.calcDifferential(context.index, RCs, nextPos, nextRc);
					double hdiff = context.hscorer.calcDifferential(context.index, RCs, nextPos, nextRc);
					double maxhdiff = -context.negatedhscorer.calcDifferential(context.index, RCs, nextPos, nextRc);
					child.gscore = diff;
					//Correct for incorrect gscore.
					rigiddiff = rigiddiff - node.gscore + node.rigidScore;
					child.rigidScore = rigiddiff;

					confLowerBound = child.gscore + hdiff;
					double confUpperbound = rigiddiff + maxhdiff;
					child.computeNumConformations(RCs);
					if (diff < confCorrection) {
						recordCorrection(confLowerBound, confCorrection - diff);
						confLowerBound = confCorrection + hdiff;
					}
					child.setBoundsFromConfLowerAndUpper(confLowerBound, confUpperbound);
					progress.reportInternalNode(child.level, child.gscore, child.getHScore(), queue.size(), children.size(), epsilonBound);
				}
				if (child.getLevel() == RCs.getNumPos()) {
					double confRigid = context.rigidscorer.calcDifferential(context.index, RCs, nextPos, nextRc);
					confRigid=confRigid-node.gscore+node.rigidScore;

					child.computeNumConformations(RCs); // Shouldn't this always eval to 1, given that we are looking at leaf nodes?
					double confCorrection = correctionMatrix.confE(child.assignments);
					double lowerbound = minimizingEmat.confE(child.assignments);
					if (lowerbound < confCorrection) {
						recordCorrection(lowerbound, confCorrection - lowerbound);
					}
					checkBounds(confCorrection, confRigid);
					child.setBoundsFromConfLowerAndUpper(confCorrection, confRigid);
					child.gscore = child.getConfLowerBound();
					confLowerBound = lowerbound;
					child.rigidScore = confRigid;
					numConfsScored++;
					progress.reportLeafNode(child.gscore, queue.size(), epsilonBound);
				}
				partialTime.stop();
				loopPartialTime += partialTime.getTimeS();


				if (Double.isNaN(child.rigidScore))
					System.out.println("Huh!?");
				SHARKStarNode SHARKStarNodeChild = curNode.makeChild(child);
				SHARKStarNodeChild.markUpdated();
				if (confLowerBound < bestChildLower) {
					bestChild = SHARKStarNodeChild;
				}
				// collect the possible children
				if (SHARKStarNodeChild.getConfSearchNode().getConfLowerBound() < 0) {
					children.add(SHARKStarNodeChild);
				}
				newNodes.add(SHARKStarNodeChild);

			}
			return bestChild;
		}
	}

	protected void boundLowestBoundConfUnderNode(SHARKStarNode startNode, List<SHARKStarNode> generatedNodes) {
		Comparator<SHARKStarNode> confBoundComparator = Comparator.comparingDouble(o -> o.getConfSearchNode().getConfLowerBound());
		PriorityQueue<SHARKStarNode> drillQueue = new PriorityQueue<>(confBoundComparator);
		drillQueue.add(startNode);

		List<SHARKStarNode> newNodes = new ArrayList<>();
		int numNodes = 0;
		Stopwatch leafLoop = new Stopwatch().start();
		Stopwatch overallLoop = new Stopwatch().start();
		while(!drillQueue.isEmpty()) {
			numNodes++;
			SHARKStarNode curNode = drillQueue.poll();
			Node node = curNode.getConfSearchNode();
			ConfIndex index = new ConfIndex(RCs.getNumPos());
			node.index(index);

			if (node.getLevel() < RCs.getNumPos()) {
				SHARKStarNode nextNode = drillDown(newNodes, curNode, node);
				newNodes.remove(nextNode);
				drillQueue.add(nextNode);
			}
			else {
				newNodes.add(curNode);
			}

			//debugHeap(drillQueue, true);
			if(leafLoop.getTimeS() > 1) {
				leafLoop.stop();
				leafLoop.reset();
				leafLoop.start();
				System.out.println(String.format("Processed %d, %s so far. Bounds are now [%12.6e,%12.6e]",numNodes, overallLoop.getTime(2),rootNode.getLowerBound(),rootNode.getUpperBound()));
			}
		}
		generatedNodes.addAll(newNodes);

	}

	protected void processPartialConfNode(List<SHARKStarNode> newNodes, SHARKStarNode curNode, Node node) {
		// which pos to expand next?
		node.index(confIndex);
		int nextPos = order.getNextPos(confIndex, RCs);
		assert (!confIndex.isDefined(nextPos));
		assert (confIndex.isUndefined(nextPos));

		// score child nodes with tasks (possibly in parallel)
		List<SHARKStarNode> children = new ArrayList<>();
		for (int nextRc : RCs.get(nextPos)) {

			if (hasPrunedPair(confIndex, nextPos, nextRc)) {
				continue;
			}

			// if this child was pruned dynamically, then don't score it
			if (pruner != null && pruner.isPruned(node, nextPos, nextRc)) {
				continue;
			}

			loopTasks.submit(() -> {

				try (ObjectPool.Checkout<ScoreContext> checkout = contexts.autoCheckout()) {
					Stopwatch partialTime = new Stopwatch().start();
					ScoreContext context = checkout.get();
					node.index(context.index);
					Node child = node.assign(nextPos, nextRc);

					// score the child node differentially against the parent node
					if (child.getLevel() < RCs.getNumPos()) {
						double confCorrection = correctionMatrix.confE(child.assignments);
						double diff = confCorrection;
						double rigiddiff = context.rigidscorer.calcDifferential(context.index, RCs, nextPos, nextRc);
						double hdiff = context.hscorer.calcDifferential(context.index, RCs, nextPos, nextRc);
						double maxhdiff = -context.negatedhscorer.calcDifferential(context.index, RCs, nextPos, nextRc);
						child.gscore = diff;
						//Correct for incorrect gscore.
						rigiddiff=rigiddiff-node.gscore+node.rigidScore;
						child.rigidScore = rigiddiff;

						double confLowerBound = child.gscore + hdiff;
						double confUpperbound = rigiddiff + maxhdiff;
						child.computeNumConformations(RCs);
						double lowerbound = minimizingEmat.confE(child.assignments);
						if(diff < confCorrection) {
							recordCorrection(confLowerBound, confCorrection - diff);
							confLowerBound = confCorrection + hdiff;
						}
						child.setBoundsFromConfLowerAndUpper(confLowerBound, confUpperbound);
						progress.reportInternalNode(child.level, child.gscore, child.getHScore(), queue.size(), children.size(), epsilonBound);
					}
					if (child.getLevel() == RCs.getNumPos()) {
						double confRigid = context.rigidscorer.calcDifferential(context.index, RCs, nextPos, nextRc);
						confRigid=confRigid-node.gscore+node.rigidScore;

						child.computeNumConformations(RCs); // Shouldn't this always eval to 1, given that we are looking at leaf nodes?
						double confCorrection = correctionMatrix.confE(child.assignments);
						double lowerbound = minimizingEmat.confE(child.assignments);

						if(lowerbound < confCorrection) {
							recordCorrection(lowerbound, confCorrection - lowerbound);
						}
						checkBounds(confCorrection,confRigid);
						child.setBoundsFromConfLowerAndUpper(confCorrection, confRigid);
						child.gscore = confCorrection;
						child.rigidScore = confRigid;
						numConfsScored++;
						progress.reportLeafNode(child.gscore, queue.size(), epsilonBound);
					}
					partialTime.stop();
					loopPartialTime+=partialTime.getTimeS();


					return child;
				}

			}, (Node child) -> {
				if(Double.isNaN(child.rigidScore))
					System.out.println("Huh!?");
				SHARKStarNode SHARKStarNodeChild = curNode.makeChild(child);
				// collect the possible children
				if (SHARKStarNodeChild.getConfSearchNode().getConfLowerBound() < 0) {
					children.add(SHARKStarNodeChild);
				}
				if (!child.isMinimized()) {
					newNodes.add(SHARKStarNodeChild);
				}
				else
					SHARKStarNodeChild.computeEpsilonErrorBounds();

				curNode.markUpdated();
			});
		}
	}


	protected void processFullConfNode(List<SHARKStarNode> newNodes, SHARKStarNode curNode, Node node) {
		double confCorrection = correctionMatrix.confE(node.assignments);
		if(node.getConfLowerBound() < confCorrection || node.gscore < confCorrection) {
			double oldg = node.gscore;
			node.gscore = confCorrection;
			recordCorrection(oldg, confCorrection - oldg);
			node.setBoundsFromConfLowerAndUpper(confCorrection, node.getConfUpperBound());
			curNode.markUpdated();
			newNodes.add(curNode);
			return;
		}
		loopTasks.submit(() -> {
					try (ObjectPool.Checkout<ScoreContext> checkout = contexts.autoCheckout()) {
						ScoreContext context = checkout.get();
						node.index(context.index);

						ConfSearch.ScoredConf conf = new ConfSearch.ScoredConf(node.assignments, node.getConfLowerBound());
						ConfAnalyzer.ConfAnalysis analysis = confAnalyzer.analyze(conf);
						Stopwatch correctionTimer = new Stopwatch().start();
						computeEnergyCorrection(analysis, conf, context.ecalc);

						double energy = analysis.epmol.energy;
						double newConfUpper = energy;
						double newConfLower = energy;
						// Record pre-minimization bounds so we can parse out how much minimization helped for upper and lower bounds
						double oldConfUpper = node.getConfUpperBound();
						double oldConfLower = node.getConfLowerBound();
						checkConfLowerBound(node, energy);
						if (newConfUpper > oldConfUpper) {
							System.err.println("Upper bounds got worse after minimization:" + newConfUpper
									+ " > " + (oldConfUpper)+". Rejecting minimized energy.");
							System.err.println("Node info: "+node);

							newConfUpper = oldConfUpper;
							newConfLower = oldConfUpper;
						}
						curNode.setBoundsFromConfLowerAndUpper(newConfLower,newConfUpper);
						double oldgscore = node.gscore;
						node.gscore = newConfLower;
						String out = "Energy = " + String.format("%6.3e", energy) + ", [" + (node.getConfLowerBound()) + "," + (node.getConfUpperBound()) + "]";
						debugPrint(out);
						curNode.markUpdated();
						synchronized(this) {
							numConfsEnergied++;
							minList.set(conf.getAssignments().length-1,minList.get(conf.getAssignments().length-1)+1);
							recordReduction(oldConfLower, oldConfUpper, energy);
							printMinimizationOutput(node, newConfLower, oldgscore);
						}


					}
					return null;
				},
				// Dummy function. We're not doing anything here.
				(Node child) -> {
					progress.reportLeafNode(node.gscore, queue.size(), epsilonBound);
					if(!node.isMinimized())
						newNodes.add(curNode);

				});
	}

	private void printMinimizationOutput(Node node, double newConfLower, double oldgscore) {
		if (printMinimizedConfs) {
			System.out.println("[" + SimpleConfSpace.formatConfRCs(node.assignments) + "]"
					+ String.format("conf:%4d, score:%12.6f, lower:%12.6f, corrected:%12.6f energy:%12.6f"
							+", bounds:[%12e, %12e], delta:%12.6f, time:%10s",
					numConfsEnergied, oldgscore, minimizingEmat.confE(node.assignments),
					correctionMatrix.confE(node.assignments), newConfLower,
					rootNode.getConfSearchNode().getSubtreeLowerBound(),rootNode.getConfSearchNode().getSubtreeUpperBound(),
					epsilonBound, stopwatch.getTime(2)));

		}
	}

	private void checkConfLowerBound(Node node, double energy) {
		if(energy < node.getConfLowerBound()) {
			System.err.println("Bounds are incorrect:" + (node.getConfLowerBound()) + " > "
					+ energy);
			if (energy < 10)
				System.err.println("The bounds are probably wrong.");
			//System.exit(-1);
		}
	}


	private void checkBounds(double lower, double upper)
	{
		if (upper < lower && upper - lower > 1e-5 && upper < 10)
			debugPrint("Bounds incorrect.");
	}

	private void computeEnergyCorrection(ConfAnalyzer.ConfAnalysis analysis, ConfSearch.ScoredConf conf,
										 ConfEnergyCalculator ecalc) {
		if(conf.getAssignments().length < 3)
			return;
		//System.out.println("Analysis:"+analysis);
		EnergyMatrix energyAnalysis = analysis.breakdownEnergyByPosition(ResidueForcefieldBreakdown.Type.All);
		EnergyMatrix scoreAnalysis = analysis.breakdownScoreByPosition(minimizingEmat);
		Stopwatch correctionTime = new Stopwatch().start();
		//System.out.println("Energy Analysis: "+energyAnalysis);
		//System.out.println("Score Analysis: "+scoreAnalysis);
		EnergyMatrix diff = energyAnalysis.diff(scoreAnalysis);
		//System.out.println("Difference Analysis " + diff);
		List<TupE> sortedPairwiseTerms2 = new ArrayList<>();
		for (int pos = 0; pos < diff.getNumPos(); pos++)
		{
			for (int rc = 0; rc < diff.getNumConfAtPos(pos); rc++)
			{
				for (int pos2 = 0; pos2 < diff.getNumPos(); pos2++)
				{
					for (int rc2 = 0; rc2 < diff.getNumConfAtPos(pos2); rc2++)
					{
						if(pos >= pos2)
							continue;
						double sum = 0;
						sum+=diff.getOneBody(pos, rc);
						sum+=diff.getPairwise(pos, rc, pos2, rc2);
						sum+=diff.getOneBody(pos2,rc2);
						TupE tupe = new TupE(new RCTuple(pos, rc, pos2, rc2), sum);
						sortedPairwiseTerms2.add(tupe);
					}
				}
			}
		}
		Collections.sort(sortedPairwiseTerms2);

		double threshhold = 0.1;
		double minDifference = 0.9;
		double triplethreshhold = 0.3;
		double maxDiff = sortedPairwiseTerms2.get(0).E;
		for(int i = 0; i < sortedPairwiseTerms2.size(); i++)
		{
			TupE tupe = sortedPairwiseTerms2.get(i);
			double pairDiff = tupe.E;
			if(pairDiff < minDifference &&  maxDiff - pairDiff > threshhold)
				continue;
			maxDiff = Math.max(maxDiff, tupe.E);
			int pos1 = tupe.tup.pos.get(0);
			int pos2 = tupe.tup.pos.get(1);
			int localMinimizations = 0;
			for(int pos3 = 0; pos3 < diff.getNumPos(); pos3++) {
				if (pos3 == pos2 || pos3 == pos1)
					continue;
				RCTuple tuple = makeTuple(conf, pos1, pos2, pos3);
				double tupleBounds = rigidEmat.getInternalEnergy(tuple) - minimizingEmat.getInternalEnergy(tuple);
				if(tupleBounds < triplethreshhold)
					continue;
				minList.set(tuple.size()-1,minList.get(tuple.size()-1)+1);
				computeDifference(tuple, minimizingEcalc);
				localMinimizations++;
			}
			numPartialMinimizations+=localMinimizations;
			progress.reportPartialMinimization(localMinimizations, epsilonBound);
		}
		correctionTime.stop();
		ecalc.tasks.waitForFinish();
	}




	private void computeDifference(RCTuple tuple, ConfEnergyCalculator ecalc) {
		computedCorrections = true;
		if(correctedTuples.contains(tuple.stringListing()))
			return;
		correctedTuples.add(tuple.stringListing());
		if(correctionMatrix.hasHigherOrderTermFor(tuple))
			return;
		minimizingEcalc.calcEnergyAsync(tuple, (minimizedTuple) -> {
			double tripleEnergy = minimizedTuple.energy;

			double lowerbound = minimizingEmat.getInternalEnergy(tuple);
			if (tripleEnergy - lowerbound > 0) {
				double correction = tripleEnergy - lowerbound;
				correctionMatrix.setHigherOrder(tuple, correction);
			}
			else
				System.err.println("Negative correction for "+tuple.stringListing());
		});
	}

	private RCTuple makeTuple(ConfSearch.ScoredConf conf, int... positions) {
		RCTuple out = new RCTuple();
		for(int pos: positions)
			out = out.addRC(pos, conf.getAssignments()[pos]);
		return out;
	}

	private void processPreminimization(ConfEnergyCalculator ecalc) {
		int maxMinimizations = 1;//parallelism.numThreads;
		List<SHARKStarNode> topConfs = getTopConfs(maxMinimizations);
		// Need at least two confs to do any partial preminimization
		if (topConfs.size() < 2) {
			queue.addAll(topConfs);
			return;
		}
		RCTuple lowestBoundTuple = topConfs.get(0).toTuple();
		RCTuple overlap = findLargestOverlap(lowestBoundTuple, topConfs, 3);
		//Only continue if we have something to minimize
		for (SHARKStarNode conf : topConfs) {
			RCTuple confTuple = conf.toTuple();
			if(minimizingEmat.getInternalEnergy(confTuple) == rigidEmat.getInternalEnergy(confTuple))
				continue;
			numPartialMinimizations++;
			minList.set(confTuple.size()-1,minList.get(confTuple.size()-1)+1);
			if (confTuple.size() > 2 && confTuple.size() < RCs.getNumPos ()){
				minimizingEcalc.tasks.submit(() -> {
					computeTupleCorrection(minimizingEcalc, conf.toTuple());
					return null;
				}, (econf) -> {
				});
			}
		}
		//minimizingEcalc.tasks.waitForFinish();
		ConfIndex index = new ConfIndex(RCs.getNumPos());
		if(overlap.size() > 3 && !correctionMatrix.hasHigherOrderTermFor(overlap)
				&& minimizingEmat.getInternalEnergy(overlap) != rigidEmat.getInternalEnergy(overlap)) {
			minimizingEcalc.tasks.submit(() -> {
				computeTupleCorrection(ecalc, overlap);
				return null;
			}, (econf) -> {
			});
		}
		queue.addAll(topConfs);
	}

	private void computeTupleCorrection(ConfEnergyCalculator ecalc, RCTuple overlap) {
		if(correctionMatrix.hasHigherOrderTermFor(overlap))
			return;
		double pairwiseLower = minimizingEmat.getInternalEnergy(overlap);
		double partiallyMinimizedLower = ecalc.calcEnergy(overlap).energy;
		progress.reportPartialMinimization(1, epsilonBound);
		if(partiallyMinimizedLower > pairwiseLower)
			synchronized (correctionMatrix) {
				correctionMatrix.setHigherOrder(overlap, partiallyMinimizedLower - pairwiseLower);
			}
		progress.reportPartialMinimization(1, epsilonBound);
	}

	private List<SHARKStarNode> getTopConfs(int numConfs) {
		List<SHARKStarNode> topConfs = new ArrayList<>();
		while (topConfs.size() < numConfs&& !queue.isEmpty()) {
			SHARKStarNode nextLowestConf = queue.poll();
			topConfs.add(nextLowestConf);
		}
		return topConfs;
	}


	private RCTuple findLargestOverlap(RCTuple conf, List<SHARKStarNode> otherConfs, int minResidues) {
		RCTuple overlap = conf;
		for(SHARKStarNode other: otherConfs) {
			overlap = overlap.intersect(other.toTuple());
			if(overlap.size() < minResidues)
				break;
		}
		return overlap;

	}

	protected void updateBound() {
		double curEpsilon = epsilonBound;
		Stopwatch time = new Stopwatch().start();
		epsilonBound = rootNode.computeEpsilonErrorBounds();
		time.stop();
		//System.out.println("Bound update time: "+time.getTime(2));
		debugEpsilon(curEpsilon);
		//System.out.println("Current epsilon:"+epsilonBound);
	}

	private boolean hasPrunedPair(ConfIndex confIndex, int nextPos, int nextRc) {

		// do we even have pruned pairs?
		PruningMatrix pmat = RCs.getPruneMat();
		if (pmat == null) {
			return false;
		}

		for (int i = 0; i < confIndex.numDefined; i++) {
			int pos = confIndex.definedPos[i];
			int rc = confIndex.definedRCs[i];
			assert (pos != nextPos || rc != nextRc);
			if (pmat.getPairwise(pos, rc, nextPos, nextRc)) {
				return true;
			}
		}
		return false;
	}

	public static class Values extends PartitionFunction.Values {

		public Values ()
		{
			pstar = MathTools.BigPositiveInfinity;
		}
		@Override
		public BigDecimal calcUpperBound() {
			return pstar;
		}

		@Override
		public BigDecimal calcLowerBound() {
			return qstar;
		}

		@Override
		public double getEffectiveEpsilon() {
			return MathTools.bigDivide(pstar.subtract(qstar), pstar, decimalPrecision).doubleValue();
		}
	}



	protected static class ScoreContext {
		public ConfIndex index;
		public AStarScorer gscorer;
		public AStarScorer hscorer;
		public AStarScorer negatedhscorer;
		public AStarScorer rigidscorer;
		public ConfEnergyCalculator ecalc;
	}

	private class SHARKStarNodeScorer implements AStarScorer {

		private EnergyMatrix emat;
		public SHARKStarNodeScorer(EnergyMatrix emat) {
		    this.emat = emat;
		}
		@Override
		public AStarScorer make() {
		    return new SHARKStarNodeScorer(emat);
		}


		public double calc(ConfIndex confIndex, Sequence seq, SimpleConfSpace confSpace) {
			return calc(confIndex, seq.makeRCs(confSpace));
		}
		/* Assumes: that the rcs contain only the sequence in question. In this case, we need only
		*  sum over all unassigned positions. Returns a lower bound on the ensemble energy.
		*  Note: I currently exponentiate and log for compatibilty. This could be optimized.*/
		@Override
		public double calc(ConfIndex confIndex, edu.duke.cs.osprey.astar.conf.RCs rcs) {
			double baseEnergy = 0;
			BoltzmannCalculator bcalc = new BoltzmannCalculator(PartitionFunction.decimalPrecision);
		    for(int definedPos: confIndex.definedPos) {
		    	int rot1 = confIndex.findDefined(definedPos);
		    	baseEnergy += emat.getEnergy(definedPos, rot1);
				for(int definedPos2: confIndex.definedPos) {
					if(definedPos2 >= definedPos)
						continue;
					int rot2 = confIndex.findDefined(definedPos2);
					baseEnergy += emat.getEnergy(definedPos, rot1, definedPos2, rot2);
				}
			}
		    BigDecimal pfuncBound = BigDecimal.ONE;
		    for (int undefinedPos1: confIndex.undefinedPos) {
		        double bestEnergy = 0;
		        for(int rot1: rcs.get(undefinedPos1)) {
					double rotEnergy = emat.getEnergy(undefinedPos1, rot1);
					for (int definedPos : confIndex.definedPos) {
						rotEnergy += emat.getEnergy(undefinedPos1, rot1, definedPos,
								confIndex.findDefined(definedPos));
					}
					for (int undefinedPos2 : confIndex.undefinedPos) {
						if (undefinedPos2 >= undefinedPos1)
							continue;
						double bestPair = Double.MAX_VALUE;
						for(int rot2: rcs.get(undefinedPos2)) {
							bestPair = Math.min(bestPair, emat.getEnergy(undefinedPos1, rot1, undefinedPos2, rot2));
						}
					}
					bestEnergy = Math.min(bestEnergy, rotEnergy);
				}
		        pfuncBound = pfuncBound.multiply(bcalc.calc(bestEnergy));
			}
			return baseEnergy+bcalc.freeEnergy(pfuncBound);
		}

		@Override
		public double calcDifferential(ConfIndex confIndex, edu.duke.cs.osprey.astar.conf.RCs rcs, int nextPos, int nextRc) {
			return 0;
		}
	}
}
