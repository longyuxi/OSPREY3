package edu.duke.cs.osprey.astar.conf;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

import edu.duke.cs.osprey.astar.AStarProgress;
import edu.duke.cs.osprey.astar.conf.order.AStarOrder;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.confspace.ConfSearch;

public class ConfAStarTree implements ConfSearch {
	
	private AStarOrder order;
	private AStarScorer gscorer;
	private AStarScorer hscorer;
	private PriorityQueue<ConfAStarNode> queue;
	private RCs rcs;
	private ConfAStarNode rootNode;
	private ConfIndex confIndex;
	private AStarProgress progress;
	
	public ConfAStarTree(AStarOrder order, AStarScorer gscorer, AStarScorer hscorer, RCs rcs) {
		this.order = order;
		this.gscorer = gscorer;
		this.hscorer = hscorer;
		this.queue = new PriorityQueue<>();
		this.rcs = rcs;
		this.rootNode = null;
		this.confIndex = new ConfIndex(this.rcs.getNumPos());
		this.progress = null;
		
		this.order.setScorers(this.gscorer, this.hscorer);
	}
	
	public void initProgress() {
		progress = new AStarProgress(rcs.getNumPos());
	}
	
	public void stopProgress() {
		progress = null;
	}
	
	@Override
	public BigInteger getNumConformations() {
    	BigInteger num = BigInteger.valueOf(1);
    	for (int pos=0; pos<rcs.getNumPos(); pos++) {
    		num = num.multiply(BigInteger.valueOf(rcs.get(pos).length));
    	}
    	return num;
	}

	@Override
	public int[] nextConf() {
		return nextLeafNode().makeConf(rcs.getNumPos());
	}
	
	public ConfAStarNode nextLeafNode() {
		
		// do we have a root node yet?
		if (rootNode == null) {
			
			rootNode = new ConfAStarNode();
			
			// pick all the single-rotamer positions now, regardless of order chosen
			// if we do them first, we basically get them for free
			// so worry about them later in the search at all
			ConfAStarNode node = rootNode;
			for (int pos=0; pos<rcs.getNumPos(); pos++) {
				if (rcs.getNum(pos) == 1) {
					node = new ConfAStarNode(node, pos, rcs.get(pos)[0]);
				}
			}
			assert (node.getLevel() == rcs.getNumTrivialPos());
			
			// score and add the tail node of the chain we just created
			scoreNode(node);
			queue.add(node);
		}
		
		while (true) {
			
			// no nodes left? we're done
			if (queue.isEmpty()) {
				return null;
			}
			
			// get the next node to expand
			ConfAStarNode node = queue.poll();
			
			// leaf node? report it
			if (node.getLevel() == rcs.getNumPos()) {
				
				// report final progress for the first leaf node, then stop reporting
				// the rest of the nodes are relatively trivial to compute
				if (progress != null) {
					progress.printProgressReport();
					progress = null;
				}
				
				return node;
			}
			
			// which pos to expand next?
			int numChildren = 0;
			confIndex.index(node);
			int nextPos = order.getNextPos(confIndex, rcs);
			assert (!confIndex.isDefined(nextPos));
			assert (confIndex.isUndefined(nextPos));
			
			for (int nextRc : rcs.get(nextPos)) {
				
				if (hasPrunedPair(confIndex, nextPos, nextRc)) {
					continue;
				}
				
				ConfAStarNode child = new ConfAStarNode(node, nextPos, nextRc);
				scoreNodeDifferential(node, child, nextPos, nextRc);
				
				// impossible node? skip it
				if (child.getScore() == Double.POSITIVE_INFINITY) {
					continue;
				}
				
				queue.add(child);
				numChildren++;
			}
			
            if (progress != null) {
            	progress.reportNode(node.getLevel(), node.getGScore(), node.getHScore(), queue.size(), numChildren);
            }
		}
	}
	
	public List<ConfAStarNode> nextLeafNodes(double maxEnergy) {
		List<ConfAStarNode> nodes = new ArrayList<>();
		while (true) {
			
			ConfAStarNode node = nextLeafNode();
			if (node == null) {
				break;
			}
			
			nodes.add(node);
			
			if (node.getGScore() >= maxEnergy) {
				break;
			}
		}
		return nodes;
	}
	
	@Override
	public List<int[]> nextConfs(double maxEnergy) {
		List<int[]> confs = new ArrayList<>();
		for (ConfAStarNode node : nextLeafNodes(maxEnergy)) {
			confs.add(node.makeConf(rcs.getNumPos()));
		}
		return confs;
	}
	
	private boolean hasPrunedPair(ConfIndex confIndex, int nextPos, int nextRc) {
		for (int i=0; i<confIndex.getNumDefined(); i++) {
			int pos = confIndex.getDefinedPos()[i];
			int rc = confIndex.getDefinedRCs()[i];
			assert (pos != nextPos || rc != nextRc);
			if (rcs.getPruneMat().getPairwise(pos, rc, nextPos, nextRc)) {
				return true;
			}
		}
		return false;
	}

	private void scoreNode(ConfAStarNode node) {
		confIndex.index(node);
		node.setGScore(gscorer.calc(confIndex, rcs));
		node.setHScore(hscorer.calc(confIndex, rcs));
	}
	
	private void scoreNodeDifferential(ConfAStarNode parent, ConfAStarNode child, int nextPos, int nextRc) {
		confIndex.index(parent);
		child.setGScore(gscorer.calcDifferential(confIndex, rcs, nextPos, nextRc));
		child.setHScore(hscorer.calcDifferential(confIndex, rcs, nextPos, nextRc));
	}
}
