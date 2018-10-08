package edu.duke.cs.osprey.markstar.framework;

import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.tools.MathTools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

public class StaticLowestEntropyOrder implements edu.duke.cs.osprey.astar.conf.order.AStarOrder {

	public final MathTools.Optimizer optimizer;
	private List<Integer> posOrder;
	private BoltzmannCalculator calculator = new BoltzmannCalculator(PartitionFunction.decimalPrecision);

	public StaticLowestEntropyOrder() {
		this(MathTools.Optimizer.Maximize);
	}

	public StaticLowestEntropyOrder(MathTools.Optimizer optimizer) {
		this.optimizer = optimizer;
	}

	private AStarScorer gscorer;
	private AStarScorer hscorer;

	@Override
	public void setScorers(AStarScorer gscorer, AStarScorer hscorer) {
		this.gscorer = gscorer;
		this.hscorer = hscorer;
	}

	@Override
	public boolean isDynamic() {
		return false;
	}

	@Override
	public int getNextPos(ConfIndex confIndex, RCs rcs) {
		if (posOrder == null) {
			posOrder = calcPosOrder(confIndex, rcs);
		}
		return posOrder.get(confIndex.node.getLevel());
	}

	private List<Integer> calcPosOrder(ConfIndex confIndex, RCs rcs) {
		// init permutation array with only undefined positions and score them
		List<Integer> undefinedOrder = new ArrayList<Integer>();
		Map<Integer,Double> scores = new TreeMap<>();
		for (int posi=0; posi<confIndex.numUndefined; posi++) {
			int pos = confIndex.undefinedPos[posi];
			undefinedOrder.add(pos);
			scores.put(pos, scorePos(confIndex, rcs, pos));
		}

		// sort positions in order of decreasing score
		Collections.sort(undefinedOrder, Comparator.comparingDouble(undefinedOrder::get));

		// prepend the defined positions to build the full order
		List<Integer> order = new ArrayList<>();
		for (int posi=0; posi<confIndex.numDefined; posi++) {
			int pos = confIndex.definedPos[posi];
			order.add(pos);
		}
		order.addAll(undefinedOrder);


		return order;
	}

	private void putResidueInPosition(List<Integer> order, int residueIndex, int orderIndex) {
	    swapPos(order, residueIndex, order.get(orderIndex));
	}

	private void swapPos(List<Integer> order, int a, int b) {
		int aIndex = order.indexOf(a);
		int bIndex = order.indexOf(b);

		order.set(bIndex, a);
		order.set(aIndex, b);
	}


	double scorePos(ConfIndex confIndex, RCs rcs, int pos) {

		// check all the RCs at this pos and aggregate the energies
		BigDecimal sum = BigDecimal.ZERO;
		double entropy = 0;
		for (int rc : rcs.get(pos)) {
			double childScore = gscorer.calcDifferential(confIndex, rcs, pos, rc)
					+ hscorer.calcDifferential(confIndex, rcs, pos, rc);
			sum = sum.add(calculator.calc(childScore));
		}
		for (int rc : rcs.get(pos)) {
			double childScore = gscorer.calcDifferential(confIndex, rcs, pos, rc)
				+ hscorer.calcDifferential(confIndex, rcs, pos, rc);
			BigDecimal childWeightedScore =  calculator.calc(childScore);
			double p = childWeightedScore.divide(sum, new MathContext(BigDecimal.ROUND_HALF_UP)).doubleValue();
			double plogp = -p*Math.log(p)/Math.log(2);
			entropy+=plogp;
		}

		return entropy;
	}
}
