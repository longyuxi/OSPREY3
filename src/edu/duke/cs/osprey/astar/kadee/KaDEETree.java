/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.astar.kadee;

import edu.duke.cs.osprey.astar.AStarNode;
import edu.duke.cs.osprey.astar.AStarTree;
import edu.duke.cs.osprey.astar.ConfTree;
import edu.duke.cs.osprey.astar.comets.LME;
import edu.duke.cs.osprey.astar.comets.UpdatedPruningMatrix;
import edu.duke.cs.osprey.confspace.HigherTupleFinder;
import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.pruning.Pruner;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.tools.ObjectIO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import org.apache.commons.lang.ArrayUtils;

/**
 * This class behaves similarly to COMETSTree and COMETSTreeSuper It optimizes
 * for the sequences with the best K* score
 *
 * @author hmn5
 */
public class KaDEETree extends AStarTree {

    public int numTreeLevels; //number of mutable residues

    LME objFcn; //objective function to minimize
    LME[] constraints; //constraints on our sequence

    public ArrayList<ArrayList<String>> AATypeOptions; //The allowed amino-acids at each level

    int numMaxMut; //number of mutatations allowed away from wtSeq (-1 means no cap)
    String wtSeq[]; //wt sequence
    //information on states
    int numStates;//how many states there are
    //they have to have the same mutable residues & AA options,
    //though the residues involved may be otherwise different

    SearchProblem[] mutableSearchProblems;//SearchProblems involved in COMETS search

    public SearchProblem nonMutableSearchProblem;

    ArrayList<ArrayList<Integer>> mutable2StatePosNums;
    //mutable2StatePosNum.get(state) maps levels in this tree to flexible positions for state
    //(not necessarily an onto mapping)

    int stateNumPos[];

    int numSeqsReturned = 0;
    int stateGMECsForPruning = 0;//how many state GMECs have been calculated for nodes that are pruned    

    //Maps the bound res num to the corresponding unbound emat
    HashMap<Integer, EnergyMatrix> boundResNumToUnboundEmat;
    //Maps the bound res num to the corresponding unbound res num
    HashMap<Integer, Integer> boundPosNumToUnboundPosNum;
    //Maps the bound res num to boolean that is true if res num is part of mutable
    //strand
    HashMap<Integer, Boolean> boundResNumToIsMutableStrand;
    //determines if two residues are on the same strand
    boolean[][] belongToSameStrand;


    public KaDEETree(int numTreeLevels, LME objFcn, LME[] constraints,
            ArrayList<ArrayList<String>> AATypeOptions, int numMaxMut, String[] wtSeq,
            int numStates, SearchProblem[] stateSP, SearchProblem nonMutableSearchProblem,
            ArrayList<ArrayList<Integer>> mutable2StatePosNums) {

        this.numTreeLevels = numTreeLevels;
        this.objFcn = objFcn;
        this.constraints = constraints;
        this.AATypeOptions = AATypeOptions;
        this.numMaxMut = numMaxMut;
        this.wtSeq = wtSeq;
        this.numStates = numStates;
        this.mutableSearchProblems = stateSP;
        this.nonMutableSearchProblem = nonMutableSearchProblem;
        this.mutable2StatePosNums = mutable2StatePosNums;

        stateNumPos = new int[numStates];
        for (int state = 0; state < numStates; state++) {
            stateNumPos[state] = stateSP[state].confSpace.numPos;
        }

        this.boundResNumToUnboundEmat = getBoundPosNumToUnboundEmat();
        this.boundPosNumToUnboundPosNum = getBoundPosNumToUnboundPosNum();
        this.boundResNumToIsMutableStrand = getBoundPosNumberToIsMutableStrand();
        this.belongToSameStrand = getSameStrandMatrix();
    }

    private double boundFreeEnergyChange(KaDEENode seqNode) {
        if (seqNode.isFullyDefined())//fully-defined sequence
        {
            printSequence(getSequence(seqNode));
            // calcSequenceScore calculates the exact LME for the sequence
            // double bound = calcSequenceScore(seqNode);

            // this calculates the COMETS bound for a fully-defined sequence
            double bound = calcLBConfTrees(seqNode, objFcn);
            System.out.println("Lower Bound: " + bound);
            return bound;
        } else {
            printSequence(getSequence(seqNode));
            // The current setup is for debugging purposes
            // exact holds the exact partial sequence score
            // kadee holds the new partial sequence bound that Pablo developed
            // this will throw an exception when the bound does not hold
            double exact = computeExactPartSeqBound(seqNode);
            double kadee = calcLBPartialSeqImproved(seqNode);
            if (kadee > exact){
                throw new RuntimeException("KaDEE Bound > Exact Bound");
            }
            return kadee;
           
        }
    }

    @Override
    public ArrayList<AStarNode> getChildren(AStarNode curNode) {
        KaDEENode seqNode = (KaDEENode) curNode;
        ArrayList<AStarNode> ans = new ArrayList<>();

        if (seqNode.isFullyDefined()) {
            seqNode.expandConfTree();
            seqNode.setScore(boundFreeEnergyChange(seqNode));
            ans.add(seqNode);
            return ans;
        } else {
            //expand next position...
            int[] curAssignments = seqNode.getNodeAssignments();

            for (int splitPos = 0; splitPos < numTreeLevels; splitPos++) {
                if (curAssignments[splitPos] < 0) {//we can split this level

                    for (int aa = 0; aa < AATypeOptions.get(splitPos).size(); aa++) {
                        int[] childAssignments = curAssignments.clone();
                        childAssignments[splitPos] = aa;
                        UpdatedPruningMatrix[] childPruneMat = new UpdatedPruningMatrix[numStates];
                        for (int state = 0; state < numStates; state++) {
                            childPruneMat[state] = doChildPruning(state, seqNode.pruneMat[state], splitPos, aa);
                        }

                        KaDEENode childNode = new KaDEENode(childAssignments, childPruneMat);

                        if (splitPos == numTreeLevels - 1) {//sequence is fully defined...make conf trees
                            makeSeqConfTrees(childNode);
                        }

                        childNode.setScore(boundFreeEnergyChange(childNode));
                        ans.add(childNode);
                    }

                    return ans;
                }
            }

            throw new RuntimeException("ERROR: Not splittable position found but sequence not fully defined...");
        }
    }


    private double calcLBPartialSeqImproved(KaDEENode seqNode) {
        SearchProblem boundSP = mutableSearchProblems[0];
        SearchProblem ligandSP = mutableSearchProblems[1];

        double gminec_p_la_pla;
        ArrayList<Integer> subsetPos_p_la_pla = getSubsetBoundPos_P_La(seqNode);
        EnergyMatrix eMat_p_la_pla = new EnergyMatrix(boundSP.emat.getSubsetMatrix(subsetPos_p_la_pla));
        PruningMatrix pruneMat_p_la_pla = new PruningMatrix(seqNode.pruneMat[0].getSubsetMatrix(subsetPos_p_la_pla));
        ConfTree cTree_p_la_pla = new ConfTree(eMat_p_la_pla, pruneMat_p_la_pla);
        int[] conf_p_la_pla = cTree_p_la_pla.nextConf();
        if (conf_p_la_pla == null) {
            gminec_p_la_pla = Double.POSITIVE_INFINITY;
        } else {
            RCTuple tup = new RCTuple(conf_p_la_pla);
            gminec_p_la_pla = eMat_p_la_pla.getInternalEnergy(tup);
        }

        double gminec_plus_lalus = Double.POSITIVE_INFINITY;
        boolean[][] intGraph_plus_lalus = createInteractionGraph_lalus_plus(seqNode);
        EnergyMatrix emat_plus_lalus = (EnergyMatrix) ObjectIO.deepCopy(boundSP.emat);
        emat_plus_lalus.updateMatrixCrossTerms(intGraph_plus_lalus);
        ConfTree cTree_plus_lalus = new ConfTree(emat_plus_lalus, seqNode.pruneMat[0]);
        int[] gmec_plus_lalus = cTree_plus_lalus.nextConf();
        if (gmec_plus_lalus != null){
            gminec_plus_lalus = emat_plus_lalus.getInternalEnergy(new RCTuple(gmec_plus_lalus));
        }
        
        
        // GMinEC(P) can be precomputed because it is a constant for the system or computed here. 
        double gminec_p = -objFcn.getConstTerm();

        
        double gminec_la_lalus = Double.NEGATIVE_INFINITY;
        boolean[][] interactionGraph_lalus = createInteractionGraph_lalus(seqNode);
        boolean[][] interactionGraph_lala = createInteractionGraph(getLigandPosNums(false), getLigandAssignedPosNums(seqNode, false), getLigandAssignedPosNums(seqNode, false));
        boolean[][] interactionGraph = addInteractionGraphs(interactionGraph_lala, interactionGraph_lalus);
        EnergyMatrix emat_unbound = (EnergyMatrix) ObjectIO.deepCopy(ligandSP.emat);
        emat_unbound.updateMatrixCrossTerms(interactionGraph);
        emat_unbound.addInternalEnergies(ligandSP.emat, getLigandAssignedPosNums(seqNode, false));
        emat_unbound.negatePairwiseEnergies(interactionGraph_lalus);
        ConfTree cTree_la_lalus = new ConfTree(emat_unbound,seqNode.pruneMat[1]);
        int[] gmec_la_lalus = cTree_la_lalus.nextConf();
        emat_unbound.negatePairwiseEnergies(interactionGraph_lalus);
        if (gmec_la_lalus != null){
            gminec_la_lalus = emat_unbound.getInternalEnergy(new RCTuple(gmec_la_lalus));
        }
        double score = gminec_p_la_pla + gminec_plus_lalus - gminec_p - gminec_la_lalus  + mutableSearchProblems[0].emat.getConstTerm() - mutableSearchProblems[1].emat.getConstTerm();
        return score;

    }


   

    /**
     * Is this flexible position assigned an amino acid?
     *
     * @param state the state (bound or unbound for KaDEE)
     * @param posNum the flexible position of the state
     * @param seqNode the sequence node
     * @return true if the flexible position is assigned
     */
    private boolean statePosAssigned(int state, int posNum, KaDEENode seqNode) {
        if (mutable2StatePosNums.get(state).contains(posNum)) {
            int treeLevel = mutable2StatePosNums.get(state).indexOf(posNum);
            int[] partSeq = seqNode.getNodeAssignments();
            return (partSeq[treeLevel] != -1);
        }
        return true;
    }

    /**
     * Calculates the max interface bound
     * @param seqNode
     * @return 
     */
    private double calcMaxInterfaceScore(KaDEENode seqNode) {
        SearchProblem boundSP = mutableSearchProblems[0];
        SearchProblem ligandSP = mutableSearchProblems[1];

        ArrayList<Integer> boundPosNums = getAllBoundPosNums();
        ArrayList<Integer> proteinBoundPosNums = getProteinPosNums(true);
        ArrayList<Integer> ligandBoundPosNums = getLigandPosNums(true);

        boolean[][] interactionGraph_protein = createInteractionGraph(boundPosNums, proteinBoundPosNums, proteinBoundPosNums);
        boolean[][] interactionGraph_p_l = createInteractionGraph(boundPosNums, proteinBoundPosNums, ligandBoundPosNums);
        boolean[][] interactionGraph = addInteractionGraphs(interactionGraph_protein, interactionGraph_p_l);

//        SearchProblem interfaceSP = boundSP.getPartialSearchProblem(boundPosNums, seqNode.pruneMat[0]);
        EnergyMatrix ematSubset = new EnergyMatrix(boundSP.emat.getSubsetMatrix(boundPosNums));
        PruningMatrix pruneMatSubset = new PruningMatrix(seqNode.pruneMat[0].getSubsetMatrix(boundPosNums));
        ematSubset.updateMatrixCrossTerms(interactionGraph);
        ematSubset.addInternalEnergies(boundSP.emat, proteinBoundPosNums);
        ematSubset.addCrossTermInternalEnergies(boundSP.emat, ligandSP.emat, ligandBoundPosNums, boundPosNumToUnboundPosNum);

        ConfTree cTree = new ConfTree(ematSubset, pruneMatSubset);
        int[] conf = cTree.nextConf();
        double gmecInterface;
        if (conf == null) {
            gmecInterface = Double.POSITIVE_INFINITY;
        } else {
            gmecInterface = ematSubset.getInternalEnergy(new RCTuple(conf));
        }

        return gmecInterface + objFcn.getConstTerm();
    }

    /**
     * Calculates the (exact) objective function value for a node This is done
     * when a node is fully defined
     *
     * @param seqNode the fully-defined sequence node
     * @return the exact value of the objective function
     */
    private double calcSequenceScore(KaDEENode seqNode) {
        if (seqNode.stateTrees[0] == null || seqNode.stateTrees[1] == null) {
            return Double.POSITIVE_INFINITY;
        }

        ConfTree boundTree = seqNode.stateTrees[0];
        EnergyMatrix boundEmat = this.mutableSearchProblems[0].emat;

        ConfTree unBoundTree = seqNode.stateTrees[1];
        EnergyMatrix unboundEmat = this.mutableSearchProblems[1].emat;

        double Ebound = boundEmat.getInternalEnergy(new RCTuple(boundTree.nextConf())) + boundEmat.getConstTerm();
        double Eunbound = unboundEmat.getInternalEnergy(new RCTuple(unBoundTree.nextConf())) + unboundEmat.getConstTerm();

        double score = Ebound - Eunbound + objFcn.getConstTerm();
//        System.out.println(score);
        seqNode.scoreSet = true;
        return score;
    }

    /**
     * For debugging purposes this computes the exact bound on a partial sequence
     * @param seqNode
     * @return 
     */
    public double computeExactPartSeqBound(KaDEENode seqNode) {
        String[] sequenceList = getSequence(seqNode);
        System.out.print("Current Node: ");
        for (int i = 0; i < sequenceList.length; i++) {
            System.out.print(sequenceList[i] + " ");
        }
        System.out.println();

        int[][] allSequence = getAllSequences(seqNode);

        double[][] sequenceExactScore = new double[allSequence.length][this.numStates];

        for (int i = 0; i < allSequence.length; i++) {
            int[] sequence = allSequence[i];
            UpdatedPruningMatrix[] pruneMatPerState = handleSequenceSpecificPruning(seqNode, sequence);
            for (int state = 0; state < this.numStates; state++) {
                PruningMatrix pruneMat = pruneMatPerState[state];
                EnergyMatrix emat = getEnergyMatrix(state);

                ConfTree tree = new ConfTree(emat, pruneMat);
                int[] gmec = tree.nextConf();
                double gmecE;
                if (gmec == null) {
                    gmecE = Double.POSITIVE_INFINITY;
                } else {
                    gmecE = emat.getInternalEnergy(new RCTuple(gmec));
                }
                sequenceExactScore[i][state] = gmecE;
            }
        }

        int seqNumMinScore = -1;
        double minScore = Double.POSITIVE_INFINITY;

        for (int i = 0; i < allSequence.length; i++) {
            double score = sequenceExactScore[i][0] - sequenceExactScore[i][1] + this.objFcn.getConstTerm();
            if (Double.isInfinite(sequenceExactScore[i][0]) || Double.isInfinite(sequenceExactScore[i][1])) {
                score = Double.POSITIVE_INFINITY;
            }
            if (score < minScore) {
                minScore = score;
                seqNumMinScore = i;
            }
        }

        int[] minSequence = allSequence[seqNumMinScore];
        System.out.print("Best Sequence Under Node: ");
        for (int i = 0; i < this.numTreeLevels; i++) {
            System.out.print(this.AATypeOptions.get(i).get(minSequence[i]) + " ");
        }
        System.out.println();
        System.out.println("Score : " + (minScore + this.objFcn.getConstTerm()));
        return minScore;
    }

    
    public double computeExactBoundStatePartSeqBound(KaDEENode seqNode) {
        String[] sequenceList = getSequence(seqNode);
        System.out.print("Current Node: ");
        for (int i = 0; i < sequenceList.length; i++) {
            System.out.print(sequenceList[i] + " ");
        }
        System.out.println();

        int[][] allSequence = getAllSequences(seqNode);

        double[] sequenceExactScore = new double[allSequence.length];

        for (int i = 0; i < allSequence.length; i++) {
            int[] sequence = allSequence[i];
            UpdatedPruningMatrix[] pruneMatPerState = handleSequenceSpecificPruning(seqNode, sequence);
            int state = 0;
            PruningMatrix pruneMat = pruneMatPerState[state];
            EnergyMatrix emat = getEnergyMatrix(state);

            ConfTree tree = new ConfTree(emat, pruneMat);
            int[] gmec = tree.nextConf();
            double gmecE;
            if (gmec == null) {
                gmecE = Double.POSITIVE_INFINITY;
            } else {
                gmecE = emat.getInternalEnergy(new RCTuple(gmec));
            }
            sequenceExactScore[i] = gmecE;

        }

        int seqNumMinScore = -1;
        double minScore = Double.POSITIVE_INFINITY;

        for (int i = 0; i < allSequence.length; i++) {
            double score = sequenceExactScore[i];
            if (Double.isInfinite(score)) {
                score = Double.POSITIVE_INFINITY;
            }
            if (score < minScore) {
                minScore = score;
                seqNumMinScore = i;
            }
        }

        System.out.println("Bound State Score : " + minScore);
        return minScore;
    }

    public double computeExactUnboundStatePartSeqBound(KaDEENode seqNode) {
        String[] sequenceList = getSequence(seqNode);
        System.out.print("Current Node: ");
        for (int i = 0; i < sequenceList.length; i++) {
            System.out.print(sequenceList[i] + " ");
        }
        System.out.println();

        int[][] allSequence = getAllSequences(seqNode);

        double[] sequenceExactScore = new double[allSequence.length];

        for (int i = 0; i < allSequence.length; i++) {
            int[] sequence = allSequence[i];
            UpdatedPruningMatrix[] pruneMatPerState = handleSequenceSpecificPruning(seqNode, sequence);
            int state = 1;
            PruningMatrix pruneMat = pruneMatPerState[state];
            EnergyMatrix emat = getEnergyMatrix(state);

            ConfTree tree = new ConfTree(emat, pruneMat);
            int[] gmec = tree.nextConf();
            double gmecE;
            if (gmec == null) {
                gmecE = Double.POSITIVE_INFINITY;
            } else {
                gmecE = emat.getInternalEnergy(new RCTuple(gmec));
            }
            sequenceExactScore[i] = gmecE;

        }

        int seqNumMinScore = -1;
        double maxScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < allSequence.length; i++) {
            double score = sequenceExactScore[i];
            if (Double.isInfinite(score)) {
                score = Double.NEGATIVE_INFINITY;
            }
            if (score > maxScore) {
                maxScore = score;
                seqNumMinScore = i;
            }
        }

        System.out.println("Unbound State Score : " + maxScore);
        return maxScore;
    }

    UpdatedPruningMatrix[] handleSequenceSpecificPruning(KaDEENode seqNode, int[] sequence) {
        UpdatedPruningMatrix[] ans = new UpdatedPruningMatrix[this.numStates];
        for (int state = 0; state < this.numStates; state++) {
            PruningMatrix parentMat = seqNode.pruneMat[state];
            UpdatedPruningMatrix updatedPruneMat = new UpdatedPruningMatrix(parentMat);
            for (int i = 0; i < this.numTreeLevels; i++) {
                String AAType = this.AATypeOptions.get(i).get(sequence[i]);
                int posNum = this.mutable2StatePosNums.get(state).get(i);

                for (int rc : parentMat.unprunedRCsAtPos(posNum)) {
                    String rcAAType = mutableSearchProblems[state].confSpace.posFlex.get(posNum).RCs.get(rc).AAType;

                    if (!rcAAType.equalsIgnoreCase(AAType)) {
                        updatedPruneMat.markAsPruned(new RCTuple(posNum, rc));
                    }
                }
            }
            ans[state] = updatedPruneMat;
        }
        return ans;
    }

    int[][] getAllSequences(KaDEENode seqNode) {
        int[] assignmnet = seqNode.getNodeAssignments();

        //Iterate over mutable positions and get the total number of sequences
        int numSequences = 1;
        //Keep track of the last assigned level
        int lastAssignedLevel = -1;
        for (int i = 0; i < this.numTreeLevels; i++) {
            if (assignmnet[i] == -1) {
                numSequences = numSequences * this.AATypeOptions.get(i).size();
            } else {
                lastAssignedLevel = i;
            }
        }

        int[] currentSeq = ArrayUtils.subarray(assignmnet, 0, lastAssignedLevel + 1);
        int[][] seqList = new int[1][currentSeq.length];
        seqList[0] = currentSeq;

        for (int mutPos = lastAssignedLevel + 1; mutPos < this.numTreeLevels; mutPos++) {
            seqList = getAllSequencesHelper(seqList, mutPos);
        }
        return seqList;
    }

    int[][] getAllSequencesHelper(int[][] currentSeqList, int level) {
        //the number of current sequences
        int numCurrentSeqs = currentSeqList.length;
        //the number of new sequences after we add all AA's from mutable pos
        int numNewSeqs = numCurrentSeqs * this.AATypeOptions.get(level).size();
        //the current length of our sequences
        int currentSeqLength = currentSeqList[0].length;
        //the new length after we add mutPos
        int newSeqLength = currentSeqLength + 1;

        //the new array of sequences
        int[][] ans = new int[numNewSeqs][currentSeqList.length + 1];

        int sequenceNum = 0;
        for (int[] sequence : currentSeqList) {
            for (int aa = 0; aa < this.AATypeOptions.get(level).size(); aa++) {
                int[] newSequence = Arrays.copyOf(sequence, newSeqLength);
                //add AA from mutPos
                newSequence[newSeqLength - 1] = aa;
                ans[sequenceNum] = newSequence;
                //iterate
                sequenceNum++;
            }
        }

        return ans;
    }

    /**
     * Prunes rotamers to reflex the new allowed amino-acids
     *
     * @param state bound vs unbound state
     * @param parentMat parent pruning matrix
     * @param splitPos position that was split
     * @param aa amino acid label for new positions that was split
     * @return updated pruning matrix
     */
    private UpdatedPruningMatrix doChildPruning(int state, PruningMatrix parentMat, int splitPos, int aa) {
        //Create an update to parentMat (without changing parentMat)
        //to reflect that splitPos has been assigned an amino-acid type

        String assignedAAType = AATypeOptions.get(splitPos).get(aa);

        UpdatedPruningMatrix ans = new UpdatedPruningMatrix(parentMat);
        int posAtState = mutable2StatePosNums.get(state).get(splitPos);

        //first, prune all other AA types at splitPos
        for (int rc : parentMat.unprunedRCsAtPos(posAtState)) {
            //HUNTER: TODO: AATYperPerRes should only be one residue for now
            //We should change this to allow for rcs at the sequence search level
            String rcAAType = mutableSearchProblems[state].confSpace.posFlex.get(posAtState).RCs.get(rc).AAType;

            if (!rcAAType.equalsIgnoreCase(assignedAAType)) {
                ans.markAsPruned(new RCTuple(posAtState, rc));
            }
        }

        //now we do singles and pairs pruning
        int numUpdates = ans.countUpdates();
        int oldNumUpdates;

        //TODO: Change pruning interval if we are doing free-energy calculations
        Pruner dee = new Pruner(mutableSearchProblems[state], ans, true, Double.POSITIVE_INFINITY,
                0.0, mutableSearchProblems[state].useEPIC, mutableSearchProblems[state].useTupExpForSearch, false);

        boolean doPairs = true;
        
        do {
            oldNumUpdates = numUpdates;
            dee.prune("GOLDSTEIN");
            if (doPairs) {
                dee.prune("GOLDSTEIN PAIRS FULL");
            }
            numUpdates = ans.countUpdates();
        } while (numUpdates > oldNumUpdates);

        return ans;
    }

    private void makeSeqConfTrees(KaDEENode node) {
        //Given a node with a fully defined sequence, build its conformational search trees
        //for each state
        //If a state has no viable conformations, leave it null, with stateUB[state] = inf

        node.stateTrees = new ConfTree[numStates];
        node.stateUB = new double[numStates];

        for (int state = 0; state < numStates; state++) {

            //first make sure there are RCs available at each position
            boolean RCsAvailable = true;
            for (int pos = 0; pos < stateNumPos[state]; pos++) {
                if (node.pruneMat[state].unprunedRCsAtPos(pos).isEmpty()) {
                    RCsAvailable = false;
                    break;
                }
            }

            if (RCsAvailable) {
                node.stateTrees[state] = new ConfTree(mutableSearchProblems[state], node.pruneMat[state], false);

                AStarNode rootNode = node.stateTrees[state].rootNode();

                int blankConf[] = new int[stateNumPos[state]];//set up root node UB
                Arrays.fill(blankConf, -1);
                rootNode.UBConf = blankConf;
                updateUB(state, rootNode, node);
                node.stateUB[state] = rootNode.UB;

                node.stateTrees[state].initQueue(rootNode);//allocate queue and add root node
            } else {//no confs available for this state!
                node.stateTrees[state] = null;
                node.stateUB[state] = Double.POSITIVE_INFINITY;
            }
        }
    }

    @Override
    public boolean isFullyAssigned(AStarNode node) {
        //HMN: TODO: We need to decide when a K* score is fully calculated

        if (!node.isFullyDefined()) {
            return false;
        }

        KaDEENode seqNode = (KaDEENode) node;
        for (int state = 0; state < numStates; state++) {
            if (seqNode.stateTrees[state] != null) {
                AStarNode bestNodeForState = seqNode.stateTrees[state].getQueue().peek();

                if (!bestNodeForState.isFullyDefined())//State GMEC calculation not done
                {
                    return false;
                }
            }
        }
        return true;

        /*
         KaDEENode seqNode = (KaDEENode) node;
         return seqNode.scoreSet;
         */
    }

    @Override
    public AStarNode rootNode() {
        int[] conf = new int[numTreeLevels];
        Arrays.fill(conf, -1);//indicates the sequence is not assigned

        PruningMatrix[] pruneMats = new PruningMatrix[numStates];
        for (int state = 0; state < numStates; state++) {
            pruneMats[state] = mutableSearchProblems[state].pruneMat;
        }

        KaDEENode root = new KaDEENode(conf, pruneMats);
        //TODO: root.setScore(boundLME(root,objFcn));
        return root;
    }

    @Override
    public boolean canPruneNode(AStarNode node) {
        //Check if node can be pruned
        //This is traditionally based on constraints, thought we could pruned nodes
        //that are provably suboptimal

        //TODO: Implement constraints as in COMETS if desired
        KaDEENode seqNode = (KaDEENode) node;

        if (numMaxMut != -1) {
            //cap on number of mutations
            int mutCount = 0;
            int assignments[] = seqNode.getNodeAssignments();

            for (int level = 0; level < numTreeLevels; level++) {
                if (assignments[level] >= 0) {//AA type at level is assigned
                    if (!AATypeOptions.get(level).get(assignments[level]).equalsIgnoreCase(wtSeq[level]))//and is different from wtSeq
                    {
                        mutCount++;
                    }
                }
            }

            if (mutCount > numMaxMut)//prune based on number of mutations
            {
                return true;
            }
        }
        //TODO: for (LME constr : constraints) {....

        return false;
    }

    public void UpdateSubsetPruningMatrix(PruningMatrix partSearchSpacePruneMat, UpdatedPruningMatrix seqNodePruneMat, ArrayList<Integer> subsetPos) {
        //Make sure subsetPos is in order
        Collections.sort(subsetPos);

        //Iterate over each position in partial search space
        for (int i = 0; i < partSearchSpacePruneMat.oneBody.size(); i++) {
            //get the original pos num in full search space
            int originalPosNum = subsetPos.get(i);
            for (int rc = 0; rc < partSearchSpacePruneMat.oneBody.get(i).size(); rc++) {
                boolean isPruned = seqNodePruneMat.getOneBody(originalPosNum, rc);
                partSearchSpacePruneMat.setOneBody(i, rc, isPruned);
            }

            for (int j = 0; j < i; j++) {
                int originalPosNumJ = subsetPos.get(j);
                for (int rcI = 0; rcI < partSearchSpacePruneMat.oneBody.get(i).size(); rcI++) {
                    for (int rcJ = 0; rcJ < partSearchSpacePruneMat.oneBody.get(j).size(); rcJ++) {
                        boolean isPruned = seqNodePruneMat.getPairwise(originalPosNum, rcI, originalPosNumJ, rcJ);
                        partSearchSpacePruneMat.setPairwise(i, rcI, j, rcJ, isPruned);
                    }
                }
            }
        }
    }

    /**
     * Creates an interaction graph over the set of residues defined by
     * allPositions Every element in subsetI is interacting with every element
     * in subsetJ interactions = {(i,j) | i in subsetI and j in subsetJ}
     *
     * @param allPositions all the positions that we are considering sorted by
     * original posNumber
     * @param subsetI list of positions interacting with subsetJ
     * @param subsetJ list of positions interacting with subsetI
     * @return interaction graph interactions = {(i,j) | i in subsetI and j in
     * subsetJ}
     */
    private boolean[][] createInteractionGraph(ArrayList<Integer> allPositions, ArrayList<Integer> subsetI, ArrayList<Integer> subsetJ) {
        int numPos = allPositions.size();
        boolean[][] interactionGraph = new boolean[numPos][numPos];

        //Initialize interactoin graph
        for (int posI = 0; posI < numPos; posI++) {
            for (int posJ = 0; posJ < numPos; posJ++) {
                interactionGraph[posI][posJ] = false;
            }
        }

        for (int posI : subsetI) {
            int newPosNum_I = allPositions.indexOf(posI);
            for (int posJ : subsetJ) {
                //We don't consider self-interactions here so we will leave it as false
                if (posI != posJ) {
                    int newPosNum_J = allPositions.indexOf(posJ);
                    interactionGraph[newPosNum_I][newPosNum_J] = true;
                    interactionGraph[newPosNum_J][newPosNum_I] = true;
                }
            }
        }
        return interactionGraph;
    }

    /**
     * Given two interaction graphs over the same graph we "add" them By this I
     * mean, (i,j) is interacting if (i,j) is interacting in either graphI or
     * graphJ
     *
     * @param interactionGraphI
     * @param interactionGraphJ
     * @return
     */
    private boolean[][] addInteractionGraphs(boolean[][] interactionGraphI, boolean[][] interactionGraphJ) {
        //Check to make sure each graph is the same size
        if (interactionGraphI.length != interactionGraphJ.length) {
            throw new RuntimeException("ERROR: Cannot add two interaction graphs of different size");
        }

        int numPos = interactionGraphI.length;
        boolean[][] interactionGraph = new boolean[numPos][numPos];
        for (int i = 0; i < numPos; i++) {
            for (int j = i + 1; j < numPos; j++) {
                interactionGraph[i][j] = (interactionGraphI[i][j] || interactionGraphJ[i][j]);
                interactionGraph[j][i] = interactionGraph[i][j];
            }
        }
        return interactionGraph;
    }

    /**
     * For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the
     * corresponding unbound energy matrix
     *
     * @return
     */
    public HashMap<Integer, EnergyMatrix> getBoundPosNumToUnboundEmat() {
        SearchProblem boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<String> resNumsBound = boundState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblem unBoundMutableState = this.mutableSearchProblems[1];
        List<String> resNumsUnboundMutable = unBoundMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblem unBoundNonMutableState = this.nonMutableSearchProblem;
        List<String> resNumsUnboundNonMutable = unBoundNonMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));
        //Map to corresponding energy matrix
        List<EnergyMatrix> unboundEmatPerPos = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? unBoundMutableState.emat : unBoundNonMutableState.emat)
                .collect(Collectors.toCollection(ArrayList::new));
        HashMap<Integer, EnergyMatrix> boundPosNumToUnboundEmat = new HashMap<>();
        for (int posNum = 0; posNum < unboundEmatPerPos.size(); posNum++) {
            boundPosNumToUnboundEmat.put(posNum, unboundEmatPerPos.get(posNum));
        }
        return boundPosNumToUnboundEmat;
    }

    /**
     * For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the
     * corresponding position number in the unbound matrix
     *
     * @return
     */
    public HashMap<Integer, Integer> getBoundPosNumToUnboundPosNum() {
        SearchProblem boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<String> resNumsBound = boundState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblem unBoundMutableState = this.mutableSearchProblems[1];
        List<String> resNumsUnboundMutable = unBoundMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblem unBoundNonMutableState = this.nonMutableSearchProblem;
        List<String> resNumsUnboundNonMutable = unBoundNonMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Map to corresponding unbound position number
        List<Integer> unboundPosNumsPerPos = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? ArrayUtils.indexOf(resNumsUnboundMutable.toArray(), posNum)
                                : ArrayUtils.indexOf(resNumsUnboundNonMutable.toArray(), posNum))
                .collect(Collectors.toCollection(ArrayList::new));

        HashMap<Integer, Integer> boundPosNumToUnboundPosNum = new HashMap<>();
        for (int posNum = 0; posNum < unboundPosNumsPerPos.size(); posNum++) {
            boundPosNumToUnboundPosNum.put(posNum, unboundPosNumsPerPos.get(posNum));
        }
        return boundPosNumToUnboundPosNum;
    }

    /**
     * For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the
     * corresponding strand number
     *
     * @return
     */
    public boolean[][] getSameStrandMatrix() {
        SearchProblem boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<String> resNumsBound = boundState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toList());

        //Get res number for each flexible position in the unbound mutable state
        SearchProblem unBoundMutableState = this.mutableSearchProblems[1];
        List<String> resNumsUnboundMutable = unBoundMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toList());

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblem unBoundNonMutableState = this.nonMutableSearchProblem;
        List<String> resNumsUnboundNonMutable = unBoundNonMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toList());
        //Map to corresponding unbound position number
        List<Integer> boundPosNumToStrandNum = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? 0 : 1)
                .collect(Collectors.toList());

        int numResidues = boundPosNumToStrandNum.size();
        boolean[][] belongToSameStrand = new boolean[numResidues][numResidues];
        for (int i = 0; i < numResidues; i++) {
            for (int j = i + 1; j < numResidues; j++) {
                if (boundPosNumToStrandNum.get(i).equals(boundPosNumToStrandNum.get(j))) {
                    belongToSameStrand[i][j] = true;
                    belongToSameStrand[j][i] = true;
                } else {
                    belongToSameStrand[i][j] = false;
                    belongToSameStrand[j][i] = false;
                }
            }
        }
        return belongToSameStrand;
    }

    /**
     * For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the
     * boolean that is true if the res is part of boolean strand and false
     * otherwise
     *
     * @return
     */
    public HashMap<Integer, Boolean> getBoundPosNumberToIsMutableStrand() {
        SearchProblem boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<String> resNumsBound = boundState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblem unBoundMutableState = this.mutableSearchProblems[1];
        List<String> resNumsUnboundMutable = unBoundMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblem unBoundNonMutableState = this.nonMutableSearchProblem;
        List<String> resNumsUnboundNonMutable = unBoundNonMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));
        //Map to corresponding unbound position number
        List<Boolean> boundPosNumIsMutable = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum))
                .collect(Collectors.toCollection(ArrayList::new));

        HashMap<Integer, Boolean> boundPosNumToIsMutableStrand = new HashMap<>();
        for (int posNum = 0; posNum < boundPosNumIsMutable.size(); posNum++) {
            boundPosNumToIsMutableStrand.put(posNum, boundPosNumIsMutable.get(posNum));
        }
        return boundPosNumToIsMutableStrand;
    }

    /**
     * This returns the list of integers corresponding to the assigned position
     * numbers of the ligand in the confSpace This is useful for computing GMECs
     * over partial spaces.
     *
     * @param seqNode the current sequence node
     * @param useBoundState do we want to position numbers corresponding to
     * bound state (true) or unbound state (false)
     * @return
     */
    private ArrayList<Integer> getLigandAssignedPosNums(KaDEENode seqNode, boolean useBoundState) {
        int[] partialSeq = seqNode.getNodeAssignments();
        //Get the mapping between mutable position in sequence node assignment and
        //the corresponding position number in the confSpace for bound or unbound ligand
        ArrayList<Integer> mutable2PosNum = useBoundState ? this.mutable2StatePosNums.get(0) : this.mutable2StatePosNums.get(1);
        //Get the corresponding searchProblem for bound or unbound ligand
        SearchProblem searchProblem = useBoundState ? this.mutableSearchProblems[0] : this.mutableSearchProblems[1];

        ArrayList<Integer> ligandAssignedPosNums = new ArrayList<>();

        //Iterate over flexible position numbers 
        for (int posNum = 0; posNum < searchProblem.confSpace.numPos; posNum++) {

            //If we are using the bound state, we must check if the position belongs to 
            //Ligand or protein
            //If we are not using the bound state then it must belong to ligand
            if (this.boundResNumToIsMutableStrand.get(posNum) || !(useBoundState)) {
                //position is on mutable strand, implying it belongs to ligand

                //Now check if the posNum is mutable
                if (mutable2PosNum.contains(posNum)) {
                    //It is mutable so get the index of mutable position wrt sequence node assignment
                    int index = mutable2PosNum.indexOf(posNum);
                    //Now we check if this mutable position is assigned
                    if (partialSeq[index] >= 0) {
                        //It is assigned so add it to our list
                        ligandAssignedPosNums.add(posNum);
                    }
                } else {//if the posNum is NOT mutable then it is assigned already assigned
                    //add since it is assigned and on ligand
                    ligandAssignedPosNums.add(posNum);
                }
            }
        }
        return ligandAssignedPosNums;
    }

    /**
     * This returns the list of integers corresponding to the assigned position
     * numbers of the ligand in the confSpace This is useful for computing GMECs
     * over partial spaces.
     *
     * @param seqNode current sequence node in tree
     * @param useBoundState do we want the posNums to correspond to the bound
     * state or unbound state
     * @return
     */
    private ArrayList<Integer> getLigandUnassignedPosNums(KaDEENode seqNode, boolean useBoundState) {
        int[] partialSeq = seqNode.getNodeAssignments();

        //Get the mapping between mutable position in sequence node assignment and
        //the corresponding position number in the confSpace for bound or unbound ligand
        ArrayList<Integer> mutable2PosNum = useBoundState ? this.mutable2StatePosNums.get(0) : this.mutable2StatePosNums.get(1);
        //Get the corresponding searchProblem for bound or unbound ligand
        SearchProblem searchProblem = useBoundState ? this.mutableSearchProblems[0] : this.mutableSearchProblems[1];

        ArrayList<Integer> ligandUnassignedPosNums = new ArrayList<>();

        //Iterate over flexible position numbers
        for (int posNum = 0; posNum < searchProblem.confSpace.numPos; posNum++) {

            //If we are using the bound state, we must check if the position belongs to 
            //Ligand or protein
            //If we are not using the bound state then it must belong to ligand
            if (this.boundResNumToIsMutableStrand.get(posNum) || !(useBoundState)) {
                //Position is on the mutable (ligand) strand

                //Now check if the posNum is mutable
                if (mutable2PosNum.contains(posNum)) {
                    //It is mutable so get the index of the mutable position wrt sequence node assignment
                    int index = mutable2PosNum.indexOf(posNum);
                    //Now we check if this mutable position is unassigned
                    if (partialSeq[index] < 0) {
                        //It is unassigned so add to our list
                        ligandUnassignedPosNums.add(posNum);
                    }
                }
            }
        }
        return ligandUnassignedPosNums;
    }

    /**
     * Returns the list of posNums corresponding to the protein residues for
     * either the bound search space or unbound search space
     *
     * @param useBoundState if true, use bound search space
     * @return
     */
    private ArrayList<Integer> getProteinPosNums(boolean useBoundState) {
        SearchProblem searchSpace = useBoundState ? mutableSearchProblems[0] : nonMutableSearchProblem;

        ArrayList<Integer> proteinPosNums = new ArrayList<>();

        for (int pos = 0; pos < searchSpace.confSpace.numPos; pos++) {
            //If we are using the bound state we must check if posNum
            //belongs to protein or ligand
            if (useBoundState) {
                //Check if pos belongs to nonmutable (protein) strand
                if (!this.boundResNumToIsMutableStrand.get(pos)) {
                    proteinPosNums.add(pos);
                }
            } else {//If we are using the unbound state, every posNum is part of protein
                proteinPosNums.add(pos);
            }
        }
        return proteinPosNums;
    }

    /**
     * Returns the list of posNums corresponding to the ligand residues for
     * either the bound search space or the unbound searchSpace
     *
     * @param useBoundState if true, use bound search space
     * @return
     */
    private ArrayList<Integer> getLigandPosNums(boolean useBoundState) {
        SearchProblem searchSpace = useBoundState ? mutableSearchProblems[0] : mutableSearchProblems[1];

        ArrayList<Integer> ligandPosNums = new ArrayList<>();

        for (int pos = 0; pos < searchSpace.confSpace.numPos; pos++) {
            if (useBoundState) {
                if (this.boundResNumToIsMutableStrand.get(pos)) {
                    ligandPosNums.add(pos);
                }
            } else {
                ligandPosNums.add(pos);
            }
        }
        return ligandPosNums;
    }

    /**
     * Returns a list of all posNums (simply 0,1,...,numPosNums-1) for the bound
     * state
     *
     * @return
     */
    private ArrayList<Integer> getAllBoundPosNums() {
        SearchProblem searchSpace = mutableSearchProblems[0];
        ArrayList<Integer> allPosNums = new ArrayList<>();
        for (int pos = 0; pos < searchSpace.confSpace.numPos; pos++) {
            allPosNums.add(pos);
        }
        return allPosNums;
    }

    private double getMAP(SearchProblem searchSpace) {
        ConfTree confTree = new ConfTree(searchSpace);

        if (searchSpace.contSCFlex) {
            throw new RuntimeException("Continuous Flexibility Not Yet Supported in KaDEE");
        }

        int[] MAPconfig = confTree.nextConf();
        if (MAPconfig == null) {
            return Double.POSITIVE_INFINITY;
        }
        double E = searchSpace.emat.getInternalEnergy(new RCTuple(MAPconfig));
        return E;
    }

    //COMETS BOUND
    private double calcLBPartialSeqCOMETS(KaDEENode seqNode, LME func) {

        int partialSeq[] = seqNode.getNodeAssignments();

        double ans = func.getConstTerm();
        //first terms for mutable residues
        for (int i = 0; i < numTreeLevels; i++) {
            double resE = Double.POSITIVE_INFINITY;

            for (int curAA : AAOptions(i, partialSeq[i])) {
                double AAE = 0;

                //get contributions to residue energy for this AA type from all states
                for (int state = 0; state < numStates; state++) {
                    if (func.coeffs[state] != 0) {

                        int statePosNum = mutable2StatePosNums.get(state).get(i);//residue number i converted to this state's flexible position numbering
                        boolean minForState = (func.coeffs[state] > 0);//minimizing (instead of maximizing) energy for this state

                        double stateAAE = Double.POSITIVE_INFINITY;

                        PruningMatrix pruneMat = seqNode.pruneMat[state];
                        EnergyMatrix eMatrix = getEnergyMatrix(state);

                        ArrayList<Integer> rotList = unprunedRCsAtAA(state, pruneMat,
                                i, statePosNum, curAA);

                        if (!minForState) {
                            stateAAE = Double.NEGATIVE_INFINITY;
                        }

                        for (int rot : rotList) {

                            double rotE = eMatrix.getOneBody(statePosNum, rot);

                            for (int pos2 = 0; pos2 < stateNumPos[state]; pos2++) {//all non-mut; seq only if < this one
                                if ((!mutable2StatePosNums.get(state).contains(pos2)) || pos2 < statePosNum) {

                                    double bestInteraction = Double.POSITIVE_INFINITY;
                                    ArrayList<Integer> rotList2 = pruneMat.unprunedRCsAtPos(pos2);

                                    if (!minForState) {
                                        bestInteraction = Double.NEGATIVE_INFINITY;
                                    }

                                    for (int rot2 : rotList2) {
                                        //rot2 known to be unpruned
                                        if (!pruneMat.getPairwise(statePosNum, rot, pos2, rot2)) {

                                            double pairwiseE = eMatrix.getPairwise(statePosNum, rot, pos2, rot2);
                                            pairwiseE += higherOrderContrib(state, pruneMat, statePosNum, rot, pos2, rot2, minForState);

                                            if (minForState) {
                                                bestInteraction = Math.min(bestInteraction, pairwiseE);
                                            } else {
                                                bestInteraction = Math.max(bestInteraction, pairwiseE);
                                            }
                                        }
                                    }

                                    rotE += bestInteraction;
                                }
                            }

                            if (minForState) {
                                stateAAE = Math.min(stateAAE, rotE);
                            } else {
                                stateAAE = Math.max(stateAAE, rotE);
                            }
                        }

                        if (Double.isInfinite(stateAAE)) {
                            //this will occur if the state is impossible (all confs pruned)
                            if (func.coeffs[state] > 0) {
                                AAE = Double.POSITIVE_INFINITY;
                            } else if (func.coeffs[state] < 0 && AAE != Double.POSITIVE_INFINITY) //if a "positive-design" (coeff>0) state is impossible, return +inf overall
                            {
                                AAE = Double.NEGATIVE_INFINITY;
                            }
                            //else AAE unchanged, since func doesn't involve this state
                        } else {
                            AAE += func.coeffs[state] * stateAAE;
                        }
                    }
                }

                resE = Math.min(resE, AAE);
            }

            ans += resE;
        }

        //now we bound the energy for the other residues for each of the states
        //(internal energy for that set of residues)
        for (int state = 0; state < numStates; state++) {

            if (func.coeffs[state] != 0) {
                ans += func.coeffs[state] * getEnergyMatrix(state).getConstTerm();

                double nonMutBound = boundStateNonMutE(state, seqNode, func.coeffs[state] > 0);
                //handle this like AAE above
                if (Double.isInfinite(nonMutBound)) {
                    //this will occur if the state is impossible (all confs pruned)
                    if (func.coeffs[state] > 0) {
                        return Double.POSITIVE_INFINITY;
                    } else if (func.coeffs[state] < 0) {
                        ans = Double.NEGATIVE_INFINITY;
                    }
                    //if a "positive-design" (coeff>0) state is impossible, return +inf overall still
                    //else ans unchanged, since func doesn't involve this state
                } else {
                    ans += func.coeffs[state] * nonMutBound;
                }
            }
        }

        return ans;
    }

    private int[] AAOptions(int pos, int assignment) {
        //which of the AA option indices for this mutable position are allowed 
        //given this assignment in nodeAssignments?

        if (assignment == -1) {//all options allowed
            int numOptions = AATypeOptions.get(pos).size();
            int[] ans = new int[numOptions];
            for (int option = 0; option < numOptions; option++) {
                ans[option] = option;
            }

            return ans;
        } else//just the assigned option
        {
            return new int[]{assignment};
        }
    }

    @Override
    public int[] outputNode(AStarNode node) {
//        printSequence(getSequence((KaDEENode) node));
        System.out.println();
        printBestSeqInfo((KaDEENode) node);
        return node.getNodeAssignments();
    }

    public String[] getSequence(KaDEENode node) {
        int[] assignments = node.getNodeAssignments();
        int numMotPos = assignments.length;
        String[] sequence = new String[numMotPos];

        for (int mutPos = 0; mutPos < numMotPos; mutPos++) {
            int aaTypeVal = assignments[mutPos];
            String aaType;
            if (aaTypeVal == -1) {
                aaType = "XXX";
            } else {
                aaType = this.AATypeOptions.get(mutPos).get(aaTypeVal);
            }
            sequence[mutPos] = aaType;
        }
        return sequence;
    }

    public void printSequence(String[] sequence) {
        StringBuffer buffer = new StringBuffer();
        for (String aaType : sequence) {
            buffer.append(" " + aaType);
        }
        System.out.println(buffer);
    }

    private EnergyMatrix getEnergyMatrix(int state) {
        if (mutableSearchProblems[state].useTupExpForSearch)//Using LUTE
        {
            return mutableSearchProblems[state].tupExpEMat;
        } else//discrete flexibility w/o LUTE
        {
            return mutableSearchProblems[state].emat;
        }
    }

    private double boundStateNonMutE(int state, KaDEENode seqNode, boolean minForState) {
        //get a quick lower or upper bound (as indicated) for the energy of the given state's
        //non-mutable residues (their intra+shell energies + pairwise between them)
        //use pruning information from seqNode
        double ans = 0;

        PruningMatrix pruneMat = seqNode.pruneMat[state];
        EnergyMatrix eMatrix = getEnergyMatrix(state);

        for (int pos = 0; pos < stateNumPos[state]; pos++) {
            if ((!mutable2StatePosNums.get(state).contains(pos))) {

                double resE = Double.POSITIVE_INFINITY;

                ArrayList<Integer> rotList = pruneMat.unprunedRCsAtPos(pos);

                if (!minForState) {
                    resE = Double.NEGATIVE_INFINITY;
                }

                for (int rot : rotList) {
                    //make sure rot isn't pruned
                    if (!pruneMat.getOneBody(pos, rot)) {

                        double rotE = eMatrix.getOneBody(pos, rot);

                        for (int pos2 = 0; pos2 < stateNumPos[state]; pos2++) {//all non-mut; seq only if < this one
                            if ((!mutable2StatePosNums.get(state).contains(pos2)) && pos2 < pos) {

                                double bestInteraction = Double.POSITIVE_INFINITY;
                                ArrayList<Integer> rotList2 = pruneMat.unprunedRCsAtPos(pos2);
                                if (!minForState) {
                                    bestInteraction = Double.NEGATIVE_INFINITY;
                                }

                                for (int rot2 : rotList2) {
                                    if (!pruneMat.getPairwise(pos, rot, pos2, rot2)) {
                                        double pairwiseE = eMatrix.getPairwise(pos, rot, pos2, rot2);

                                        pairwiseE += higherOrderContrib(state, pruneMat, pos, rot, pos2, rot2, minForState);

                                        if (minForState) {
                                            bestInteraction = Math.min(bestInteraction, pairwiseE);
                                        } else {
                                            bestInteraction = Math.max(bestInteraction, pairwiseE);
                                        }
                                    }
                                }

                                rotE += bestInteraction;
                            }
                        }

                        if (minForState) {
                            resE = Math.min(resE, rotE);
                        } else {
                            resE = Math.max(resE, rotE);
                        }
                    }
                }

                ans += resE;
            }
        }

        return ans;
    }

    double higherOrderContrib(int state, PruningMatrix pruneMat,
            int pos1, int rc1, int pos2, int rc2, boolean minForState) {
        //higher-order contribution for a given RC pair in a given state, 
        //when scoring a partial conf

        EnergyMatrix emat = getEnergyMatrix(state);
        HigherTupleFinder<Double> htf = emat.getHigherOrderTerms(pos1, rc1, pos2, rc2);

        if (htf == null) {
            return 0;//no higher-order interactions
        } else {
            RCTuple curPair = new RCTuple(pos1, rc1, pos2, rc2);
            return higherOrderContrib(state, pruneMat, htf, curPair, minForState);
        }
    }

    double higherOrderContrib(int state, PruningMatrix pruneMat, HigherTupleFinder<Double> htf,
            RCTuple startingTuple, boolean minForState) {
        //recursive function to get bound on higher-than-pairwise terms
        //this is the contribution to the bound due to higher-order interactions
        //of the RC tuple startingTuple (corresponding to htf)

        double contrib = 0;

        //to avoid double-counting, we are just counting interactions of starting tuple
        //with residues before the "earliest" one (startingLevel) in startingTuple
        //"earliest" means lowest-numbered, except non-mutating res come before mutating
        int startingLevel = startingTuple.pos.get(startingTuple.pos.size() - 1);

        for (int iPos : htf.getInteractingPos()) {//position has higher-order interaction with tup
            if (posComesBefore(iPos, startingLevel, state)) {//interaction in right order
                //(want to avoid double-counting)

                double levelBestE = Double.POSITIVE_INFINITY;//best value of contribution
                //from tup-iPos interaction

                if (!minForState) {
                    levelBestE = Double.NEGATIVE_INFINITY;
                }

                ArrayList<Integer> allowedRCs = pruneMat.unprunedRCsAtPos(iPos);

                for (int rc : allowedRCs) {

                    RCTuple augTuple = startingTuple.addRC(iPos, rc);

                    if (!pruneMat.isPruned(augTuple)) {

                        double interactionE = htf.getInteraction(iPos, rc);

                        //see if need to go up to highers order again...
                        HigherTupleFinder htf2 = htf.getHigherInteractions(iPos, rc);
                        if (htf2 != null) {
                            interactionE += higherOrderContrib(state, pruneMat, htf2, augTuple, minForState);
                        }

                        //besides that only residues in definedTuple or levels below level2
                        if (minForState) {
                            levelBestE = Math.min(levelBestE, interactionE);
                        } else {
                            levelBestE = Math.max(levelBestE, interactionE);
                        }
                    }
                }

                contrib += levelBestE;//add up contributions from different interacting positions iPos
            }
        }

        return contrib;
    }

    private boolean posComesBefore(int pos1, int pos2, int state) {
        //Does pos1 come "before" pos2?  (Ordering: non-mut pos, then mut pos, in ascending order)
        //These are flexible positions for the given state
        boolean isMut1 = mutable2StatePosNums.get(state).contains(pos1);
        boolean isMut2 = mutable2StatePosNums.get(state).contains(pos2);

        if (isMut1 && !isMut2) {
            return false;
        }
        if (isMut2 && !isMut1) {
            return true;
        }

        //ok they are in the same category (mut or not)
        return pos1 < pos2;
    }

    private ArrayList<Integer> unprunedRCsAtAA(int state, PruningMatrix pruneMat,
            int mutablePos, int statePosNum, int curAA) {
        //List the RCs of the given position in the given state
        //that come with the indicated AA type (indexed in AATypeOptions)

        ArrayList<Integer> unprunedRCs = pruneMat.unprunedRCsAtPos(statePosNum);
        ArrayList<Integer> ans = new ArrayList<>();

        String curAAType = AATypeOptions.get(mutablePos).get(curAA);

        for (int rc : unprunedRCs) {
            String rcAAType = mutableSearchProblems[state].confSpace.posFlex.get(statePosNum).RCs.get(rc).AAType;

            if (rcAAType.equalsIgnoreCase(curAAType))//right type
            {
                ans.add(rc);
            }
        }

        return ans;
    }

    private ArrayList<Integer> getSubsetBoundPos_P_La(KaDEENode node) {
        ArrayList<Integer> subsetPos_p_la = new ArrayList<>();
        subsetPos_p_la.addAll(getProteinPosNums(true));
        subsetPos_p_la.addAll(getLigandAssignedPosNums(node, true));
        Collections.sort(subsetPos_p_la);
        return subsetPos_p_la;
    }

    private boolean[][] createInteractionGraph_lalus_plus(KaDEENode node) {
        ArrayList<Integer> allPos = getAllBoundPosNums();
        ArrayList<Integer> ligandAssigned = getLigandAssignedPosNums(node, true);
        ArrayList<Integer> ligandUnassigned = getLigandUnassignedPosNums(node, true);
        ArrayList<Integer> protein = getProteinPosNums(true);

        boolean[][] interactionGraph_plus_bound = createInteractionGraph(allPos, protein, ligandUnassigned);
        boolean[][] interactionGraph_lalus_bound = createInteractionGraph(allPos, ligandAssigned, ligandUnassigned);
        boolean[][] interactionGraph = addInteractionGraphs(interactionGraph_plus_bound, interactionGraph_lalus_bound);

        return interactionGraph;
    }

    private boolean[][] createInteractionGraph_lalus(KaDEENode node) {
        ArrayList<Integer> ligandUnbound = getLigandPosNums(false);
        ArrayList<Integer> ligandUnassigned = getLigandUnassignedPosNums(node, false);
        ArrayList<Integer> ligandAssigned = getLigandAssignedPosNums(node, false);

        return createInteractionGraph(ligandUnbound, ligandUnassigned, ligandAssigned);
    }

    private double calcLBConfTrees(KaDEENode seqNode, LME func) {
        //here the sequence is fully defined
        //so we can bound func solely based on lower and upper bounds (depending on func coefficient sign)
        //of the GMECs for each state, which can be derived from the front node of each state's ConfTree
        double ans = func.constTerm;
        for (int state = 0; state < numStates; state++) {
            if (func.coeffs[state] > 0) {//need lower bound

                if (seqNode.stateTrees[state] == null)//state and sequence impossible
                {
                    return Double.POSITIVE_INFINITY;
                }

                ans += func.coeffs[state] * seqNode.stateTrees[state].getQueue().peek().getScore();
            } else if (func.coeffs[state] < 0) {//need upper bound

                ConfTree curTree = seqNode.stateTrees[state];

                if (curTree == null) {//state and sequence impossible
                    //bound would be +infinity
                    ans = Double.NEGATIVE_INFINITY;
                    continue;
                }

                //make sure stateUB is updated, at least based on the current best node in this state's tree
                PriorityQueue<AStarNode> curExpansion = curTree.getQueue();

                AStarNode curNode = curExpansion.peek();

                if (Double.isNaN(curNode.UB)) {//haven't calculated UB, so calculate and update stateUB
                    while (!updateUB(state, curNode, seqNode)) {
                        //if UB not calc'd yet, curNode.UBConf is from curNode's parent
                        //if updateUB fails then the node has an inevitable clash...remove it from the tree
                        curExpansion.poll();
                        if (curExpansion.isEmpty()) {//no more nodes, so no available states!
                            //bound would be +infinity
                            seqNode.stateUB[state] = Double.POSITIVE_INFINITY;
                            ans = Double.NEGATIVE_INFINITY;
                            curNode = null;
                            break;
                        }

                        curNode = curExpansion.peek();
                        if (!Double.isNaN(curNode.UB)) {
                            break;
                        }
                    }
                }

                if (curNode == null) {//no nodes left for this state
                    ans = Double.NEGATIVE_INFINITY;
                    continue;
                }

                seqNode.stateUB[state] = Math.min(seqNode.stateUB[state], curNode.UB);

                ans += func.coeffs[state] * seqNode.stateUB[state];
            }

            //shell-shell energies may differ between states!
            //THIS DOUBLE-COUNTS BC SCORES ALREADY INCLUDE CONST TERM
            //ans += func.coeffs[state]*getEnergyMatrix(state).getConstTerm();
        }

        return ans;
    }

    boolean updateUB(int state, AStarNode expNode, KaDEENode seqNode) {
        //Get an upper-bound on the node by a little FASTER run, generating UBConf
        //store UBConf and UB in expNode
        //expNode is in seqNode.stateTrees[state]
        //we'll start with the starting conf (likely from a parent) if provided
        //return true unless no valid conf is possible...then false

        int assignments[] = expNode.getNodeAssignments();
        ArrayList<ArrayList<Integer>> allowedRCs = new ArrayList<>();

        for (int pos = 0; pos < stateNumPos[state]; pos++) {

            ArrayList<Integer> posOptions = new ArrayList<>();

            if (assignments[pos] == -1) {
                posOptions = seqNode.pruneMat[state].unprunedRCsAtPos(pos);
                if (posOptions.isEmpty())//no options at this position
                {
                    return false;
                }
            } else//assigned
            {
                posOptions.add(assignments[pos]);
            }

            allowedRCs.add(posOptions);
        }

        int startingConf[] = expNode.UBConf;
        int[] UBConf = startingConf.clone();
        //ok first get rid of anything in startingConf not in expNode's conf space,
        //replace with the lowest-intra+shell-E conf
        for (int level = 0; level < stateNumPos[state]; level++) {

            if (!allowedRCs.get(level).contains(startingConf[level])) {
                //if( ! levelOptions.get(level).get(expNode.conf[level]).contains( startingConf[level] ) ){

                double bestISE = Double.POSITIVE_INFINITY;
                int bestRC = allowedRCs.get(level).get(0);
                for (int rc : allowedRCs.get(level)) {
                    double ise = getEnergyMatrix(state).getOneBody(level, rc);
                    if (ise < bestISE) {
                        bestISE = ise;
                        bestRC = rc;
                    }
                }

                UBConf[level] = bestRC;
            }
        }

        double curE = getEnergyMatrix(state).confE(UBConf);
        boolean done = false;

        while (!done) {

            done = true;

            for (int level = 0; level < stateNumPos[state]; level++) {

                int testConf[] = UBConf.clone();

                for (int rc : allowedRCs.get(level)) {
                    testConf[level] = rc;

                    //if(!canPrune(testConf)){//pruned conf unlikely to be good UB
                    //would only prune if using pruned pair flags in A*
                    double testE = getEnergyMatrix(state).confE(testConf);
                    if (testE < curE) {
                        curE = testE;
                        UBConf[level] = rc;
                        done = false;
                    }
                    //}
                }
            }
        }

        expNode.UBConf = UBConf;
        expNode.UB = getEnergyMatrix(state).confE(UBConf);

        return true;
    }

    void printBestSeqInfo(KaDEENode seqNode) {
        //About to return the given fully assigned sequence from A*
        //provide information
        System.out.println("SeqTree: A* returning conformation; lower bound = " + seqNode.getScore() + " nodes expanded: " + numExpanded);

        numSeqsReturned++;

        System.out.print("Sequence: ");

        for (int level = 0; level < numTreeLevels; level++) {
            System.out.print(AATypeOptions.get(level).get(seqNode.getNodeAssignments()[level]) + " ");
        }
        System.out.println();

        /*
         //provide state GMECs, specified as rotamers (AA types all the same of course)
         for (int state = 0; state < numStates; state++) {
         System.out.print("State " + state);

         if (seqNode.stateTrees[state] == null) {
         System.out.println(" has an unavoidable clash.");
         } else {
         System.out.print(" RCs: ");
         int conf[] = seqNode.stateTrees[state].getQueue().peek().getNodeAssignments();
         for (int pos = 0; pos < stateNumPos[state]; pos++) {
         System.out.print(conf[pos] + " ");
         }

         System.out.println("Energy: "
         + getEnergyMatrix(state).confE(conf));
         }
         }
         */
        int numSeqDefNodes = 0;
        for (AStarNode node : getQueue()) {
            if (node.isFullyDefined()) {
                numSeqDefNodes++;
            }
        }

        System.out.println();
        System.out.println(numExpanded + " expanded; " + getQueue().size() + " nodes in tree, of which "
                + numSeqDefNodes + " are fully defined; "
                + numPruned + " pruned.");

        /*
         int stateGMECsRet = numSeqsReturned * numStates;
         int stateGMECsInTree = countGMECsInTree();
         int totGMECsCalcd = stateGMECsRet + stateGMECsInTree + stateGMECsForPruning;
         System.out.println(totGMECsCalcd + " state GMECs calculated: " + stateGMECsRet + " returned, " + stateGMECsInTree
         + " in tree, " + stateGMECsForPruning + " for pruned sequences.");
         */
    }

    int countGMECsInTree() {
        //count how many state GMECs have been calculated and are in nodes in the tree--
        //that is, how many ConfTrees at sequence nodes have been expanded to the points
        //that their lowest-bound node is fully expanded (and thus is their GMEC)
        //for comparison, regular A* needs to calculate the GMEC for every (non-clashing)
        //(sequence,state) pair
        //Note this only counts state GMECs currently in the tree
        int count = 0;

        for (AStarNode node : getQueue()) {
            count += countStateGMECs((KaDEENode) node);
        }

        return count;
    }

    int countStateGMECs(KaDEENode seqNode) {
        //how many states for this node have GMECs calculated?
        int count = 0;

        if (seqNode.stateTrees != null) {//fully defined sequence, so there are state trees
            for (ConfTree ct : seqNode.stateTrees) {
                if (ct != null) {
                    AStarNode bestNode = ct.getQueue().peek();
                    if (bestNode.isFullyDefined()) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    boolean isProtein(int posNum) {
        return getProteinPosNums(true).contains(posNum);
    }
}
