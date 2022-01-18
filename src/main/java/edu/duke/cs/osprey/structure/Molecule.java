/*
** This file is part of OSPREY 3.0
** 
** OSPREY Protein Redesign Software Version 3.0
** Copyright (C) 2001-2018 Bruce Donald Lab, Duke University
** 
** OSPREY is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License version 2
** as published by the Free Software Foundation.
** 
** You should have received a copy of the GNU General Public License
** along with OSPREY.  If not, see <http://www.gnu.org/licenses/>.
** 
** OSPREY relies on grants for its development, and since visibility
** in the scientific literature is essential for our success, we
** ask that users of OSPREY cite our papers. See the CITING_OSPREY
** document in this distribution for more information.
** 
** Contact Info:
**    Bruce Donald
**    Duke University
**    Department of Computer Science
**    Levine Science Research Center (LSRC)
**    Durham
**    NC 27708-0129
**    USA
**    e-mail: www.cs.duke.edu/brd/
** 
** <signature of Bruce Donald>, Mar 1, 2018
** Bruce Donald, Professor of Computer Science
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
 * A molecule is a list of {@link Residue} and their alternates
 *
 * @author mhall44
 */
public class Molecule implements Serializable {
    
    private static final long serialVersionUID = -2709516483892891323L;

    public String name = null;
    
    public Residues residues;

    public ArrayList<Fragment> fragments = new ArrayList<Fragment>();
    public boolean fragmented = false;

    private Map<Integer,ArrayList<Residue>> alternates;
    
    //also might have secondary structure elements...

    //Molecule will usually be generated by PDBFileReader.readPDBFile
    public Molecule() {
    	this(new Residues());
    }
    
    public Molecule(Residues residues) {
        this.residues = residues;
        alternates = new HashMap<>();
    }
    
    public Molecule(Molecule other){
        this(other, true);
    }
    
    public Molecule(Molecule other, boolean redoInterResBonds) {
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
        if(redoInterResBonds)
            markInterResBonds();
    }
    
    public Residue getResByPDBResNumber(String resNum) {
        return residues.getOrThrow(resNum);
    }
    
    public Residue getResByPDBResNumberOrNull(String resNum){
        return residues.getOrNull(resNum);
    }
    
    public List<Residue> getResRangeByPDBResNumber(String firstResNum, String lastResNum) {
    	
    		//check that the residue numbers exist in the molecule, and add chain numbers if needed to match molecule
	    firstResNum = residues.getOrThrow(firstResNum).getPDBResNumber();
	    lastResNum = residues.getOrThrow(lastResNum).getPDBResNumber();
	    	
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
    	Set<String> normalizedResNums = resNums.stream()
			.map((resNum) -> Residues.normalizeResNum(resNum))
			.collect(Collectors.toSet());
    	return residues.stream()
    		.filter((res) -> normalizedResNums.contains(Residues.normalizeResNum(res.getPDBResNumber())))
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
        
	    String lastResNum = residues.getOrThrow(termini[1]).getPDBResNumber();
	    //make sure it's there, and add chain ID if needed to match molecule

        while ( ! curRes.getPDBResNumber().equalsIgnoreCase(lastResNum) ) {//not at other end

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
    
    public Residue getResByFullName(String fullName){
        for(Residue res : residues){
            if(res.fullName.equalsIgnoreCase(fullName))
                return res;
        }
        throw new RuntimeException("ERROR: Can't find residue with full name "+fullName);
    }

    public void markInterResBonds(){
        for(Residue res : residues)
            res.template.interResBonding.connectInterResBonds(res, true);
        for(Residue res : residues)
            res.interResBondsMarked = true;
    }

    //TODO: add fragmentation code
    public void fragment(){
        for (int i = 1; i < this.residues.size() - 1; i++)
        {
            // create fragment i
            Fragment frag_i = new Fragment();
            frag_i.parent = this;

            // add resiue i-1, i, i+1 to fragment
            this.residues.get(i-1).copyToMol(frag_i, false);
            this.residues.get(i).copyToMol(frag_i, false);
            this.residues.get(i+1).copyToMol(frag_i, false);

            // set terminus
            if(i == 1){
                frag_i.amino_terminus = true;
            }
            if(i == this.residues.size()-2){
                frag_i.carboxyl_terminus = true;
            }

            // cap fragment
            frag_i.cap();

            // name fragment
            frag_i.name = this.name + "_fragment_" + i;

            // add fragment to list
            this.fragments.add(frag_i);
        }

        this.fragmented = true;
    }
}
