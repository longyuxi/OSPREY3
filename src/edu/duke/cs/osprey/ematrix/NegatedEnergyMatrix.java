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

package edu.duke.cs.osprey.ematrix;

import edu.duke.cs.osprey.confspace.SimpleConfSpace;

public class NegatedEnergyMatrix extends ProxyEnergyMatrix {

	public NegatedEnergyMatrix(SimpleConfSpace confSpace, EnergyMatrix target) {
		super(confSpace, target);
	}

	@Override
	public double getConstTerm() {
		return -super.getConstTerm();
	}

	@Override
	public void setConstTerm(double val) {
		super.setConstTerm(-val);
	}

	@Override
	public Double getOneBody(int pos, int rc) {
		return -super.getOneBody(pos, rc);
	}

	@Override
	public void setOneBody(int pos, int rc, Double val) {
		super.setOneBody(pos, rc, -val);
	}

	@Override
	public Double getPairwise(int pos1, int rc1, int pos2, int rc2) {
		return -super.getPairwise(pos1, rc1, pos2, rc2);
	}

	@Override
	public void setPairwise(int pos1, int rc1, int pos2, int rc2, Double val) {
		super.setPairwise(pos1, rc1, pos2, rc2, -val);
	}
}
