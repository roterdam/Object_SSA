package OSSAInstructions;

import java.util.HashMap;
import java.util.Iterator;

import OBJS.OSSAObject;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstructionFactory;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.intset.OrdinalSet;


/**
 * Compensates controlphis.
 * Inserted with putphiinstructions, if ptsTo set has more than one ASI(ALlocation Site Identifier).
 * @author yash
 *
 */
public class DPhiOSSAInstruction extends SSAInstruction{

	public OSSAObject defObj;
	public HashMap<InstanceKey, OSSAObject> argObjs;
	/*public DPhiOSSAInstruction(OSSAObject defObj, OrdinalSet<InstanceKey>ptsTo) {
		this.defObj = defObj;
		for(Iterator<InstanceKey>ASIs = ptsTo.iterator();ASIs.hasNext();) {
			InstanceKey asi = ASIs.next();
			
		}
	}*/
	public DPhiOSSAInstruction(OSSAObject defObj, HashMap<InstanceKey, OSSAObject>argsObjs) {
		this.defObj = defObj;
		this.argObjs = argsObjs;
	}
	
	public String toString() {
		StringBuffer strBuff = new StringBuffer();
		strBuff.append(defObj);
		strBuff.append(" = dphi(");
		/*for(Iterator<InstanceKey>ASIs = argObjs.keySet().iterator();ASIs.hasNext();) {
			InstanceKey asi= ASIs.next();
//			strBuff.append( argObjs.get(asi).toString(asi)+", ");
			strBuff.append( argObjs.get(asi).toString()+", ");
		}*/
		for(Iterator<InstanceKey>argobs= argObjs.keySet().iterator();argobs.hasNext();) {
			InstanceKey asi = argobs.next();
			strBuff.append( argObjs.get(asi)+"_"+asi+", ");
		}
		strBuff.deleteCharAt((strBuff.length())-2); 	//deletes last ','
		strBuff.append(")");
		return strBuff.toString();
	}
	@Override
	public SSAInstruction copyForSSA(SSAInstructionFactory insts, int[] defs,
			int[] uses) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int hashCode() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean isFallThrough() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String toString(SymbolTable symbolTable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void visit(IVisitor v) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * defauls psudo constructor
	 * @param result
	 * @param params
	 * @throws IllegalArgumentException
	 *//*
	public DPhiOSSAInstruction(int result, int[] params)
			throws IllegalArgumentException {
		super(result, params);
		// TODO Auto-generated constructor stub
	}
	
	public DPhiOSSAInstruction() {
		
	}*/
	
	

}