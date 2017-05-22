/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.structure;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import edu.duke.cs.osprey.restypes.HardCodedResidueInfo;

/**
 *
 * @author mhall44
 */
public class Molecule implements Serializable {
    
    private static final long serialVersionUID = -2709516483892891323L;
    
    public Residues residues;
    private Map<Integer,ArrayList<Residue>> alternates;
    
    //also might have secondary structure elements...

    //Molecule will usually be generated by PDBFileReader.readPDBFile
    public Molecule(){
        residues = new Residues();
        alternates = new HashMap<>();
    }
    
    public Molecule(Molecule other) {
    	this();
        
        // make a deep copy of the residues
        for (Residue residue : other.residues) {
            residue = new Residue(residue);
            residue.molec = this;
            this.residues.add(residue);
        }
        for (Map.Entry<Integer,ArrayList<Residue>> entry : other.alternates.entrySet()) {
            ArrayList<Residue> val = new ArrayList<>();
            for (Residue residue : entry.getValue()) {
                residue = new Residue(residue);
                val.add(residue);
            }
            this.alternates.put(entry.getKey(), val);
        }
        
        // re-do all the inter-res bonds
        HardCodedResidueInfo.markInterResBonds(this);
    }
    
    public Residue getResByPDBResNumber(String resNum) {
        return residues.getOrThrow(resNum);
    }
    
    public Residue getResByPDBResNumberOrNull(String resNum){
        return residues.getOrNull(resNum);
    }
    
    public List<Residue> getResRangeByPDBResNumber(String firstResNum, String lastResNum) {
    	
    	List<Residue> residues = new ArrayList<>();
    	
    	boolean isInside = false;
    	for (Residue res : this.residues) {
    		
    		String resNum = res.getPDBResNumber();
    		
    		if (resNum.equals(firstResNum)) {
    			isInside = true;
    		}
    		
    		if (isInside) {
    			residues.add(res);
    		}
    		
    		if (resNum.equals(lastResNum)) {
    			break;
    		}
    	}
    	
    	return residues;
    }
    
    public List<Residue> getResiduesByPDBResNumbers(Iterable<String> resNums) {
    	Set<String> resNumsSet = new HashSet<>();
    	for (String resNum : resNums) {
    		resNumsSet.add(resNum);
    	}
    	return getResiduesByPDBResNumbers(resNumsSet);
    }
    
    public List<Residue> getResiduesByPDBResNumbers(Set<String> resNums) {
    	return residues.stream()
    		.filter((res) -> resNums.contains(res.getPDBResNumber()))
    		.collect(Collectors.toList());
    }
    
    public void appendResidue(Residue res){
        //Add a residue to the end of the molecule
    	res.molec = this;
        res.indexInMolecule = residues.size();
        residues.add(res);
    }
    
    public void addAlternate(int resIndex, Residue res)
    {
        if(!alternates.containsKey(resIndex))
            alternates.put(resIndex, new ArrayList<Residue>());
        res.molec = this;
        res.indexInMolecule = resIndex;
        alternates.get(resIndex).add(res);
    }
    
    public List<Residue> getAlternates(int resIndex) {
        List<Residue> residues = alternates.get(resIndex);
        if (residues == null) {
            residues = Collections.emptyList();
        }
        return residues;
    }
    
    public void deleteResidue(int resIndex){
        //delete the residue with the specified index in residues
        residues.remove(resIndex);
        alternates.remove(resIndex);
        //this changes the indexInMolecule for all subsequent residues
        for(int i=resIndex; i<residues.size(); i++) {
            residues.get(i).indexInMolecule--;
            if (alternates.containsKey(i)) {
				for (Residue altRes : alternates.get(i)) {
					altRes.indexInMolecule--;
				}
            }
        }
    }
    
    public void deleteResidues(Collection<String> resNames) {
        
        // iterate backwards to make deletion easier
        for (int i=residues.size() - 1; i >= 0; i--) {
            Residue res = residues.get(i);
            if (resNames.contains(res.fullName)) {
                deleteResidue(i);
            }
        }
    }
    
    
    public ArrayList<Residue> resListFromTermini(String[] termini, ArrayList<String> flexibleRes){
        //Return a list of residues given the PDB numbers of the first and last
        //If flexibleRes isn't null, make sure all these residues are flexible
        //(used for rot/trans strands and BBFreeBlocks)
        
        ArrayList<Residue> resList = new ArrayList<>();//res in current moving strand
            
        Residue curRes = getResByPDBResNumber(termini[0]);
        resList.add(curRes);

        while ( ! curRes.getPDBResNumber().equalsIgnoreCase(termini[1]) ) {//not at other end

            int curIndex = curRes.indexInMolecule;
            if(curIndex==residues.size()-1){
                throw new RuntimeException("ERROR: Reached end of molecule"
                        + " in rot/trans strand or BBFreeBlock without finding res "+termini[1]);
            }

            curRes = residues.get( curRes.indexInMolecule+1 );
            String curPDBNum = curRes.getPDBResNumber();
            if(flexibleRes != null){
                if( ! flexibleRes.contains(curPDBNum) )
                    throw new RuntimeException("ERROR: Res "+curPDBNum+" in rot/trans strand or BBFreeBlock but not flexible!");
            }

            resList.add(curRes);
        }
        
        return resList;
    }
    
    @Override
    public int hashCode() {
        List<Integer> hashes = new ArrayList<>();
        for (Residue residue : residues) {
            hashes.add(Arrays.hashCode(residue.coords));
        }
        return hashes.hashCode();
    }
}
