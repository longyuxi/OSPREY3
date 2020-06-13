package edu.duke.cs.osprey.coffee.seqdb;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.impl.operationservice.Operation;
import edu.duke.cs.osprey.coffee.Serializers;
import edu.duke.cs.osprey.confspace.MultiStateConfSpace;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.tools.MathTools.BigDecimalBounds;

import java.io.IOException;


public class SaveOperation extends Operation {

	public static class SequencedSum {

		public final int[] seq;
		public final BigDecimalBounds[] boundsByState;

		public SequencedSum(int[] seq, BigDecimalBounds[] boundsByState) {
			this.seq = seq;
			this.boundsByState = boundsByState;
		}
	}

	public static class UnsequencedSum {

		public final int unsequencedIndex;
		public final BigDecimalBounds bounds;

		public UnsequencedSum(int unsequencedIndex, BigDecimalBounds bounds) {
			this.unsequencedIndex = unsequencedIndex;
			this.bounds = bounds;
		}
	}

	public int numSequencedStates;
	public SequencedSum[] sequencedSums;
	public UnsequencedSum[] unsequencedSums;

	public SaveOperation() {
		numSequencedStates = 0;
		sequencedSums = null;
		unsequencedSums = null;
	}

	public SaveOperation(MultiStateConfSpace confSpace, Batch batch) {

		numSequencedStates = confSpace.sequencedStates.size();

		sequencedSums = batch.sequencedSums.entrySet().stream()
			.map(entry -> {
				Sequence seq = entry.getKey();
				SeqInfo info = entry.getValue();
				assert (info.zSumBounds.length == numSequencedStates);
				return new SequencedSum(seq.rtIndices, info.zSumBounds);
			})
			.toArray(SequencedSum[]::new);

		unsequencedSums = batch.unsequencedSums.entrySet().stream()
			.map(entry -> {
				int unsequencedIndex = entry.getKey();
				BigDecimalBounds bounds = entry.getValue();
				return new UnsequencedSum(unsequencedIndex, bounds);
			})
			.toArray(UnsequencedSum[]::new);
	}

	@Override
	public final boolean returnsResponse() {
		return false;
	}

	@Override
	public String getServiceName() {
		return SeqDB.ServiceName;
	}

	@Override
	protected void writeInternal(ObjectDataOutput out)
	throws IOException {
		super.writeInternal(out);

		SeqDB seqdb = getService();
		Serializers.hazelcastSeqDBSerializeDeNovo(out, seqdb.confSpace, this);
	}

	@Override
	protected void readInternal(ObjectDataInput in)
	throws IOException {
		super.readInternal(in);

		Serializers.hazelcastSeqDBDeserializeDeNovo(in, this);
	}

	@Override
	public final void run() {
		SeqDB seqdb = getService();
		seqdb.commitBatch(this);
	}
}