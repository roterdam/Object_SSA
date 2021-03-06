package OSSA;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Stack;

import OBJS.OSSAObject;
import OSSAInstructions.ArgPhiOSSAInstruction;
import OSSAInstructions.CreateOSSAInstruction;
import OSSAInstructions.DPhiOSSAInstruction;
import OSSAInstructions.FuncOSSAInstruction;
import OSSAInstructions.GetFieldOSSAInstruction;
import OSSAInstructions.InvokeOSSAInstruction;
import OSSAInstructions.OutsideObjOSSAInstruction;
import OSSAInstructions.PutFieldOSSAInstruction;
import OSSAInstructions.PutPhiOSSAInstruction;
import OSSAInstructions.RetPhiOSSAInstruction;
import OSSAInstructions.ReturnOSSAInstruction;

import com.ibm.wala.analysis.typeInference.TypeAbstraction;
import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.shrikeBT.NewInstruction;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAIndirectionData;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSACFG.ExceptionHandlerBasicBlock;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.graph.dominators.DominanceFrontiers;
import com.ibm.wala.util.intset.OrdinalSet;
import com.ibm.wala.util.strings.StringStuff;

public class CopyOfObjectSSA {

	/**
	 * Version no assigned to next definition of OSSAObject in ObjectSSA
	 */
	public int objectVersionNoNext = 1;
	/**
	 * The call site identifier for function calls. 
	 * might be used to link function call phis with function call OSSA statements
	 */
	public int funcCallSiteNoNext = 1;
	public  ClassHierarchy cha;
	public IR ir;
	
	/**
	 * SSACFG for the ir.
	 */
	public SSACFG cfg;
	/**
	 * Pointer analysis OSSAObject for getting the heap instances. 
	 */
	public  PointerAnalysis pa;
	
	/**
	 * the heap model corresponding to pointer-analysis OSSAObject pa. Used for getting the heap instances.
	 */
	public  HeapModel hm;
	
	public CGNode node;
	
	public ObjectSSADefUse DefUse = new ObjectSSADefUse();
	
	/**
	 * an empty object representing the nullobj in ossa.
	 */
	public static OSSAObject nullobj = new OSSAObject(0);
	/**
	 * RDF contains dominance frontiers of all basic blocks
	 */
	public  DominanceFrontiers<BasicBlock> RDF;
	public  String methodName;
	public IMethod method;
	/**
	 * Constructor for ObjectSSA. Generates separate OSSA for separate methods
	 * @param cha {@link ClassHierarchy}
	 * @param ir {@link IR}
	 * @param pa {@link PointerAnalysis}
	 * @param hm {@link HeapModel}
	 * @param cg {@link CallGraph}
	 * @param m {@link IMethod}
	 */
	public CopyOfObjectSSA(ClassHierarchy cha, CallGraph cg, PointerAnalysis pa, HeapModel hm, IMethod m, IR ir){//, String psFiles, String dotFiles){
		try{
			this.cha = cha;
			this.cfg= ir.getControlFlowGraph();
			this.ir = ir;
			this.pa = pa;
			this.hm = hm;
			this.method = m;
			this.methodName = method.getSignature();
			this.node = cg.getNode(method, Everywhere.EVERYWHERE);
//			this.psFiles = psFiles;
//			this.dotFiles = dotFiles;
			instrMap = new HashMap<SSAInstruction, SSAInstruction>();
			RDF = (new DominatorTree< BasicBlock>(cfg)).createDominanceFrontiers();		//RDF contains dominancefrontiers
			//System.out.println("\n\n"+RDF+"\n\n");
//			CGNode node = cg.getNode(method, Everywhere.EVERYWHERE);
//			IR ir = node.getIR();
//			System.out.println("IR for method:"+methodName+"\n"+ir.toString());
//			(new pdfIr()).printIr(cha, ir, methodName,psFiles, dotFiles);
//			translator(ir,node);
			insertPutPhi();
			rename();
			populateOSSAInstructions();
		}catch(Exception e){
			e.printStackTrace();
		}
	}
	
	/**
	 * Stores ctrl-Phis for all scalars in basic bolck, taken directly from WALA Ir.
	 */
	public HashMap<BasicBlock, LinkedList<SSAPhiInstruction>> ctrlPhiInstructions = new HashMap<BasicBlock, LinkedList<SSAPhiInstruction>>();
	
	/**
	 * Stores Putphis for all objects in corresponding basic-blocks.
	 * MIght be redundant with handledPhismap
	 */
//	public  Hashtable<BasicBlock, HashMap<OSSAObject,PutPhiOSSAInstruction>> objectPutPhiInstructions = new Hashtable<BasicBlock, HashMap<OSSAObject,PutPhiOSSAInstruction>>();
	public HashMap<BasicBlock, LinkedList<PutPhiOSSAInstruction>> objectPutPhiInstructions = new HashMap<BasicBlock, LinkedList<PutPhiOSSAInstruction>>();
	/**
	 * Instruction label mapped to all pointsTO set defined through this phi. 
	 * Stored for each basic-block contaning putφ.
	 * Might be redundant with objectputphiinstructions
	 * TODO check for redundancy
	 */
	public HashMap<BasicBlock, HashMap<SSAInstruction, OrdinalSet<InstanceKey>>> handledPhisMap =  new HashMap<BasicBlock, HashMap<SSAInstruction,OrdinalSet<InstanceKey>>>();
	
	/**
	 * Allocation Site of OSSAObject mapped to put-φ instruction label. 
	 * Stored for each basic-block contaning put-φs.
	 */
	public HashMap<BasicBlock, HashMap<InstanceKey, SSAInstruction>> allocationPhiMap = new HashMap<BasicBlock, HashMap<InstanceKey,SSAInstruction>>();
	
	/**
	 * First part of algorithm
	 * inserts empty put-phi instructions in input SSA Ir.
	 * TODO check for robustness of phi insertion especially with processed list.
	 */
	public void insertPutPhi(){
		SSAInstruction [] instructions = ir.getInstructions();
		for (int i = 0; i <= cfg.getMaxNumber(); i++) {
			BasicBlock bb = cfg.getNode(i);
		    int start = bb.getFirstInstructionIndex();
		    int end = bb.getLastInstructionIndex();
		    if (bb instanceof ExceptionHandlerBasicBlock) {
		      continue;
		    }
		    
		    for (int j = start; j <= end; j++) {
		        
		    	  if (instructions[j] != null) {
		                	
		        	SSAInstruction instructn = instructions[j];
		        	SSAPutInstruction putInstrctn;
		        	int defVn;
		        	if (instructn instanceof SSAPutInstruction) {
						putInstrctn = (SSAPutInstruction) instructn;
						defVn = putInstrctn.getUse(0);
					}
		        	else if(instructn instanceof SSANewInstruction) {
		        		defVn = ((SSANewInstruction)instructn).getDef();
		        	}
		        	else if (instructn instanceof SSAGetInstruction) {
		        		SSAGetInstruction getFieldInstrctn = (SSAGetInstruction) instructn;
						if(getFieldInstrctn.isStatic()) {
							defVn = getFieldInstrctn.getDef();
						}
						else continue;
		        	}
		        	else if(instructn instanceof SSAInvokeInstruction) {
		        		SSAInvokeInstruction ivinstrctn = (SSAInvokeInstruction) instructn;
		        		//for objects passed as arguments
		        		int noofparameters = ivinstrctn.getNumberOfUses();
		        		for(int k=0;k<noofparameters;k++){
							int param = ivinstrctn.getUse(k);
							TypeReference tr = findType(ir, param).getTypeReference();
							if(!tr.isPrimitiveType()){
								
							}
		        		}
		        		//for objects returned by function call
		        		if(ivinstrctn.hasDef()) {
		        			int defValueNumber = ivinstrctn.getDef();
							TypeReference tr = findType(ir, defValueNumber).getTypeReference();
							if(tr.isReferenceType()) {
								defVn = defValueNumber;
							}
							else 
								continue;
		        		}
		        		else
		        			continue;
		        			
		        	}
		        	else
		        		continue;
						PointerKey ptrKeyDef = hm.getPointerKeyForLocal(node,defVn);
				    	OrdinalSet<InstanceKey> ptsToDef = pa.getPointsToSet(ptrKeyDef);
				    	HashSet<BasicBlock> worklist = new HashSet<BasicBlock>();
				    	worklist.add(bb);
				    	HashSet<BasicBlock> processed = new HashSet<BasicBlock>();
				    	while(!worklist.isEmpty()) {
				    		for(Iterator<BasicBlock>elems = worklist.iterator();elems.hasNext();) {
				    			BasicBlock nxt = elems.next();
				    			worklist.remove(nxt);
				    			
				    				for(Iterator<BasicBlock> domFrontiers= RDF.getDominanceFrontier(nxt);domFrontiers.hasNext();){
										BasicBlock d = domFrontiers.next();
										if(processed.contains(d))
											continue;
										processed.add(d);
										if(d.isExitBlock())
											continue;
										if(!handledPhisMap.containsKey(d))
											handledPhisMap.put(d, new HashMap<SSAInstruction, OrdinalSet<InstanceKey>>());
										for(Iterator<OrdinalSet<InstanceKey>>handledPhiElems = handledPhisMap.get(d).values().iterator(); handledPhiElems.hasNext();){
											OrdinalSet<InstanceKey>handledPhi = handledPhiElems.next();
											if(equateASIs(ptsToDef, handledPhi))
												continue;
										}
										
										PutPhiOSSAInstruction putPhiOSSAInstrctn ;
										if (instructn instanceof SSAPutInstruction) 
											putPhiOSSAInstrctn = new PutPhiOSSAInstruction(ptsToDef, cfg.getPredNodeCount(d),findType(ir, defVn).getTypeReference(),false);
										else
											putPhiOSSAInstrctn = new PutPhiOSSAInstruction(ptsToDef, cfg.getPredNodeCount(d),findType(ir, defVn).getTypeReference(),true);
			//							objectPutPhiInstructions.put(d, putPhiOSSAInstrctn);
										
										handledPhisMap.get(d).put(putPhiOSSAInstrctn, ptsToDef);
										
										if(!objectPutPhiInstructions.containsKey(d))
											objectPutPhiInstructions.put(d, new LinkedList<PutPhiOSSAInstruction>());
										objectPutPhiInstructions.get(d).add(putPhiOSSAInstrctn);
										
										if(!allocationPhiMap.containsKey(d))
											allocationPhiMap.put(d, new HashMap<InstanceKey, SSAInstruction>());
										for(Iterator<InstanceKey> ASIs = ptsToDef.iterator();ASIs.hasNext();){
											InstanceKey asi = ASIs.next();
											if(!allocationPhiMap.get(d).containsKey(asi))
												allocationPhiMap.get(d).put(asi, putPhiOSSAInstrctn);
										}
										
										worklist.add(d);
										//DONE insert putphis iteratively into next domfrontiers also.
									}
				    			
				    		}
							
				    	}
					//}//if (instructn instanceof SSAPutInstruction)
		    	  }//if (instructions[j] != null)
		    }//for (int j = start; j <= end; j++)
		}//for (int i = 0; i <= cfg.getMaxNumber(); i++)
	}//insertputPhi()
	
	public void insertPutPhiHelper(int defVn) {
		
	}
	
	
	/**
	 * HashTable for storing translated OSSAinstructions corresponding to WALA IR SSA instructions.
	 * When there is no scalar instruction then new OSSAinstruction is stored against the same OSSAinstruction.
	 * THe new SSAinstruction is created for example when we have phis because of definitions created by put instructions and for d-phi and u-phi instructions
	 * Also control-phis for objects are @deprecated.
	 */
	public  HashMap<SSAInstruction, SSAInstruction> instrMap; 

	/**
	 * Stacks for different allocation-sites, each stack ASI has only oneelement in its pointsTo set
	 * but OrdinalSet just for ease of implementation from walaIR. 
	 */
	public HashMap<InstanceKey, Stack<OSSAObject>> objStack = new HashMap<InstanceKey, Stack<OSSAObject>>();
	public void rename() {
		renameArgs();
		renameBody(cfg.entry());
	}
	public FuncOSSAInstruction funcossainstrctn;
	/**
	 * Cretes Objects for arguments to the function
	 */
	public void renameArgs() {
		int noofparams = ir.getNumberOfParameters();
		funcossainstrctn= new FuncOSSAInstruction(noofparams,ir.getMethod().getName().toString());//getSignature());
		for(int j = 0;j<noofparams;j++){
			int paramvalueno = ir.getParameter(j);
			funcossainstrctn.vns[j]=paramvalueno;
			TypeReference paramtype = ir.getParameterType(j);
			funcossainstrctn.paramtypes.put(paramvalueno, paramtype);
			if(!paramtype.isPrimitiveType()){
				PointerKey ptrKey = hm.getPointerKeyForLocal(node,paramvalueno);
				OrdinalSet<InstanceKey> ptsTo = pa.getPointsToSet(ptrKey);
				OSSAObject nwobj = createObj(paramtype, ptsTo);	
				funcossainstrctn.args[j] = nwobj;
//				funcossainstrctn.argObjs.put(paramvalueno, nwobj);
				DefUse.storeDef(nwobj, funcossainstrctn);
				
			}
		}
	}
	
	/**
	 * Implements second part of algorithm. Called after put-phi insertions
	 * All heap objects are identified and then renamed.
	 * Also translates simple SSA IR into ObjectSSA IR.
	 */
	public void renameBody(BasicBlock bb){
		
		SSAInstruction [] instructns = ir.getInstructions();
		
		//TODO Check- Store the current state of objectStack
		HashMap<InstanceKey, OSSAObject>stackview =  new HashMap<InstanceKey, OSSAObject>();
		for(Iterator<InstanceKey>stackASIs= objStack.keySet().iterator();stackASIs.hasNext();) {
			InstanceKey asi = stackASIs.next();
			if(!objStack.get(asi).isEmpty())
			stackview.put(asi, objStack.get(asi).peek());
		}
		
		
		int start = bb.getFirstInstructionIndex();
		int end = bb.getLastInstructionIndex();
		
		/**
		 * Control-phis for scalars
		 */
		for (Iterator<SSAPhiInstruction> it = bb.iteratePhis(); it.hasNext();) {
			SSAPhiInstruction phiInstructn= (SSAPhiInstruction) it.next();
	    	int valueNumber = phiInstructn.getDef();
	    	if(findType(ir, valueNumber).getTypeReference().isPrimitiveType()) {
	    		if(!ctrlPhiInstructions.containsKey(bb))
					ctrlPhiInstructions.put(bb, new LinkedList<SSAPhiInstruction>());
				ctrlPhiInstructions.get(bb).add(phiInstructn);
	    	}
	    	 
		}
		
		/**
		 * DONE Iterate through putphis before other instructions in basicblock. 
		 */
		if(objectPutPhiInstructions.containsKey(bb))
			for(Iterator<PutPhiOSSAInstruction>putphiinstrctns = objectPutPhiInstructions.get(bb).iterator();putphiinstrctns.hasNext();) {
				PutPhiOSSAInstruction putphiinstrctn = putphiinstrctns.next();
				for(Iterator<InstanceKey>ASIs = putphiinstrctn.putPhiArgs.keySet().iterator();ASIs.hasNext();) {
					InstanceKey asi = ASIs.next();
					if(putphiinstrctn.putPhiArgs.get(asi).isEmpty()) {
						putphiinstrctn.putPhiArgs.get(asi).put(bb,objStack.get(asi).peek());
						DefUse.storeUse(objStack.get(asi).peek(), putphiinstrctn);
					}
				}
				OSSAObject newPutPhiObj = createObj(putphiinstrctn.concreteType, putphiinstrctn.ptsTo);
				putphiinstrctn.defObj =  newPutPhiObj;
				DefUse.storeDef(newPutPhiObj, putphiinstrctn);
				
			}
		
			
		/*for(Iterator<ObjectPhiInstruction> objphiinstrctns = iterateObjPhis(bb);objphiinstrctns.hasNext();){
	    	  ObjectPhiInstruction objphiinstrctn = objphiinstrctns.next();
	    	  objphiinstrctn.defVersionNo = objphiinstrctn.defObj.VersionNo++;
	    	  objectStack.get(objphiinstrctn.defObj).push(objphiinstrctn.defVersionNo);
	    	  storeDef(objphiinstrctn.defObj, objphiinstrctn);
	    }
		*/
		
		
		
		for (int j = start; j <= end; j++) {
	        
	    	  if (instructns[j] != null) {
	                	
	        	SSAInstruction instructn = instructns[j];
//	        	SSAInstruction ossainstrctn = instrMap.get(instructn);
//	        	if(ossainstrctn==null||instructn.toString().contentEquals(ossainstrctn.toString()))
//	        		continue;
	        	if(instructn==null)
	        		continue;
	        	//TODO If possible handle all the uses of object in any type of instruction here only.
	        	int noofuses = instructn.getNumberOfUses();
	        	
	        	
	        	//TODO handle different types of instructions
	        	if (instructn instanceof SSANewInstruction) {
					SSANewInstruction new_instrctn = (SSANewInstruction) instructn;
					int new_valueNumber = new_instrctn.getDef();
					
					PointerKey ptrKey = hm.getPointerKeyForLocal(node,
							new_valueNumber);
					OrdinalSet<InstanceKey> ptsTo = pa.getPointsToSet(ptrKey);
					assert (ptsTo!=null && ptsTo.size()==1):"New statement without allocation site identifier";
					OSSAObject nwobj = createObj(new_instrctn.getConcreteType(), ptsTo);
					/*//Create Stack
					for(Iterator<InstanceKey>ASIs = ptsTo.iterator();ASIs.hasNext();) {
						InstanceKey asi = ASIs.next();
						objStack.put( asi, new Stack<OSSAObject>());
						//Create Object and insert into Stack
						nwobj= new OSSAObject(objectVersionNoNext++, new_instrctn
										.getConcreteType(), ptsTo);
						objStack.get(asi).push(nwobj);
						//Create CreateOSSAInstruction and store it in instrMap.
						
					}*/
					CreateOSSAInstruction newCreateOSSAInstrctn = new CreateOSSAInstruction(new_instrctn, nwobj);
					instrMap.put(new_instrctn, newCreateOSSAInstrctn);
					DefUse.storeDef(nwobj, newCreateOSSAInstrctn);
					
					continue;
				}
	        	//DONE Complete PUT and Get Instructions, what if used object has same objects on all stacks. 
	        	//and check for correct object version  numbering
	        	if (instructn instanceof SSAPutInstruction) {
					SSAPutInstruction putFieldInstrctn = (SSAPutInstruction) instructn;
					int def_ValueNumber = putFieldInstrctn.getUse(0);
					//Check if object has only one or more allocation sites associated with it.
					//put dphis in those cases
					PointerKey ptrKey = hm.getPointerKeyForLocal(node,
							def_ValueNumber);
					OrdinalSet<InstanceKey> ptsTo = pa.getPointsToSet(ptrKey);
					InstanceKey prevASI=null;
					InstanceKey newASI=null;
					OSSAObject contaningObj = null;
					Boolean insertDphi = false;
					//Check if all ASIs are same, then no need to insert dphi.
					for(Iterator<InstanceKey> ASIs = ptsTo.iterator();ASIs.hasNext();) {
						prevASI = newASI;
						newASI = ASIs.next();
						contaningObj =objStack.get(newASI).peek();
						if(prevASI==null)
							continue;		
						 
						if(contaningObj.equals(objStack.get(prevASI).peek())) {
							
							continue;
						}
						else {
							insertDphi=true;
							break;
						}							
					}
					DPhiOSSAInstruction dphi=null;
					if(insertDphi) {
						//TODO insert Dphi
						dphi = createDPhi(findType(ir, def_ValueNumber).getTypeReference(),ptsTo);
						contaningObj = dphi.defObj;
						DefUse.storeDef(contaningObj, dphi);
					}
					
					//Make LHS and RHS Objects.
					OSSAObject defObj = createObj(contaningObj.classType, ptsTo);
					
					
					
					FieldReference field = putFieldInstrctn.getDeclaredField();
					PutFieldOSSAInstruction  putOSSAinstrctn;
					if(field.getFieldType().isReferenceType()) {
						int fieldValueNumber = putFieldInstrctn.getUse(1);
						PointerKey fieldPtrKey = hm.getPointerKeyForLocal(node,
								fieldValueNumber);
						OrdinalSet<InstanceKey> fieldPtsTo = pa.getPointsToSet(fieldPtrKey);
						putOSSAinstrctn = new PutFieldOSSAInstruction(putFieldInstrctn, defObj, contaningObj, getUseObjs(fieldPtsTo), dphi);
					}
					else {
						putOSSAinstrctn = new PutFieldOSSAInstruction(putFieldInstrctn, defObj, contaningObj, dphi);
					}
					DefUse.storeDef(defObj, putOSSAinstrctn);
					DefUse.storeUse(contaningObj, putOSSAinstrctn);
					if(putOSSAinstrctn.fieldRefObjs!=null)
					for(Iterator<OSSAObject>useobjs = putOSSAinstrctn.fieldRefObjs.values().iterator();useobjs.hasNext();) {
						OSSAObject useobj = useobjs.next();
						DefUse.storeUse(useobj, putOSSAinstrctn);
					}
					instrMap.put(putFieldInstrctn, putOSSAinstrctn);
					
					continue;
				}
	        	
	        	if (instructn instanceof SSAGetInstruction) {
					SSAGetInstruction getFieldInstrctn = (SSAGetInstruction) instructn;
					GetFieldOSSAInstruction  getOSSAinstrctn;
					if(getFieldInstrctn.isStatic()) {
						int defvn = getFieldInstrctn.getDef();
						OSSAObject staticobj=nullobj;
//						if(findType(ir, defvn).getTypeReference().is)
						PointerKey defPtrKey = hm.getPointerKeyForLocal(node,
								defvn);
						OrdinalSet<InstanceKey> defPtsTo = pa.getPointsToSet(defPtrKey);
						for(Iterator<InstanceKey>ASIs = defPtsTo.iterator();ASIs.hasNext();) {
							InstanceKey asi = ASIs.next();
							if(!objStack.containsKey(asi)) {
								objStack.put(asi, new Stack<OSSAObject>());
								staticobj = new OSSAObject(objectVersionNoNext++, asi.getConcreteType().getReference(), asi);
								objStack.get(asi).push(staticobj);
								
							}
							staticobj = objStack.get(asi).peek();
						}
						assert(staticobj!=null):"StaticObject is null; some error";
						getOSSAinstrctn = new GetFieldOSSAInstruction(getFieldInstrctn, staticobj);
						instrMap.put(getFieldInstrctn, getOSSAinstrctn);
						continue;
						
					}
						
					int containerValueNumber = getFieldInstrctn.getUse(1);
					PointerKey containerPtrKey = hm.getPointerKeyForLocal(node,
							containerValueNumber);
					OrdinalSet<InstanceKey> containerPtsTo = pa.getPointsToSet(containerPtrKey);
					
					FieldReference field = getFieldInstrctn.getDeclaredField();
					
					if(field.getFieldType().isReferenceType()) {
						int fieldValueNumber = getFieldInstrctn.getDef();
						PointerKey fieldPtrKey = hm.getPointerKeyForLocal(node,
								fieldValueNumber);
						OrdinalSet<InstanceKey> fieldPtsTo = pa.getPointsToSet(fieldPtrKey);
						getOSSAinstrctn = new GetFieldOSSAInstruction(getFieldInstrctn, getUseObjs(containerPtsTo), getUseObjs(fieldPtsTo));
					}
					else {
						getOSSAinstrctn = new GetFieldOSSAInstruction(getFieldInstrctn, getUseObjs(containerPtsTo));
					}
					for(Iterator<OSSAObject>useobjs = getOSSAinstrctn.containerObjs.values().iterator();useobjs.hasNext();) {
						OSSAObject useobj = useobjs.next();
						DefUse.storeUse(useobj, getOSSAinstrctn);
					}
					if(getOSSAinstrctn.fieldRefObjs!=null)
						for(Iterator<OSSAObject>useobjs = getOSSAinstrctn.fieldRefObjs.values().iterator();useobjs.hasNext();) {
							OSSAObject useobj = useobjs.next();
							DefUse.storeUse(useobj, getOSSAinstrctn);
						}
					instrMap.put(getFieldInstrctn, getOSSAinstrctn);
					continue;
				}
	        	
	        	if (instructn instanceof SSAReturnInstruction){
	        		SSAReturnInstruction retInstrctn = (SSAReturnInstruction)instructn;
	        		int valueNumber = retInstrctn.getResult();
	        		
	        		if(valueNumber == -1){
	        			instrMap.put(retInstrctn, retInstrctn);
	        			continue;
	        		}
	        		// objnumbers++;
	        		if(findType(ir, valueNumber).getTypeReference().isReferenceType()) {
		        		PointerKey ptrKey = hm.getPointerKeyForLocal(node,
		        				valueNumber);
		        		OrdinalSet<InstanceKey> ptsTo = pa.getPointsToSet(ptrKey);
		        		ReturnOSSAInstruction retOSSAInstrctn = new ReturnOSSAInstruction(retInstrctn, getUseObjs(ptsTo));
		        		instrMap.put(retInstrctn, retOSSAInstrctn);
		        		for(Iterator<OSSAObject>useobjs = retOSSAInstrctn.retObj.values().iterator();useobjs.hasNext();) {
							OSSAObject useobj = useobjs.next();
							DefUse.storeUse(useobj, retOSSAInstrctn);
						}
	        		}
	        		
	        	}
	        	
	        	if(instructn instanceof SSAInvokeInstruction ){
					
					SSAInvokeInstruction ivinstrctn = (SSAInvokeInstruction) instructn;
					if(ivinstrctn.getDeclaredTarget().getName().toString().contains("println"))
						continue;
					//args of function call
					int noofparameters = ivinstrctn.getNumberOfUses();
					ArrayList<HashMap<InstanceKey, OSSAObject>> args = new ArrayList<HashMap<InstanceKey,OSSAObject>>();
					ArrayList<OSSAObject> changedArgs = new ArrayList<OSSAObject>();
					ArrayList<ArgPhiOSSAInstruction> argPhiInstrctns = new ArrayList<ArgPhiOSSAInstruction>();
//					InvokeOSSAInstruction invokeOSSAInstrctn = new InvokeOSSAInstruction(ivinstrctn, defObj, args, changedArgs);
					for(int k=0;k<noofparameters;k++){
						int param = ivinstrctn.getUse(k);
						TypeReference tr = findType(ir, param).getTypeReference();
						if(!tr.isPrimitiveType()){
							PointerKey ptrKey = hm.getPointerKeyForLocal(node, param);
							OrdinalSet<InstanceKey> ptsTo = pa.getPointsToSet(ptrKey);
							args.add(getUseObjs(ptsTo));
//							OSSAObject retObj = createObj(tr, ptsTo);
							//TODO Check Insert argphis()
							ArgPhiOSSAInstruction nwArgPhi = createArgPhi(tr, ptsTo, args.get(k));
							argPhiInstrctns.add(nwArgPhi);
							changedArgs.add(nwArgPhi.defObj);
//							invokeOSSAInstrctn.argsarray[k] = 
						}
						else {
							args.add(null);
							changedArgs.add(null);
							argPhiInstrctns.add(null);
						}
					}
					
					// function call returns some value or object.
					OSSAObject defObj = null;
					LinkedList<OutsideObjOSSAInstruction> outsideObjInstructions = new LinkedList<OutsideObjOSSAInstruction>();
					RetPhiOSSAInstruction retPhi =null;
					if(ivinstrctn.hasDef()) {
						int defValueNumber = ivinstrctn.getDef();
						TypeReference tr = findType(ir, defValueNumber).getTypeReference();
						if(tr.isReferenceType()) {
		        			PointerKey ptrDef = hm.getPointerKeyForLocal(node, defValueNumber);
							OrdinalSet<InstanceKey> ptsTo = pa.getPointsToSet(ptrDef);
							//TODO Insert retPhi()
							
							boolean insertRetPhi=false;
							InstanceKey prevASI , newASI = null;
							OSSAObject contaningObj;
							for(Iterator<InstanceKey> ASIs = ptsTo.iterator();ASIs.hasNext();) {
								InstanceKey asi = ASIs.next();
								if(!objStack.containsKey(asi)) {
									OSSAObject newObj = new OSSAObject(objectVersionNoNext++, tr, ptsTo);
									objStack.put( asi, new Stack<OSSAObject>());
//											asi.getConcreteType().
									OutsideObjOSSAInstruction outsideObjInstrctn = new OutsideObjOSSAInstruction(newObj);
									DefUse.storeDef(newObj, outsideObjInstrctn);
									outsideObjInstructions.add(outsideObjInstrctn);
									objStack.get(asi).push(newObj);	
								}
								
								prevASI = newASI;
								newASI = asi;
								contaningObj =objStack.get(newASI).peek();
								if(prevASI==null)
									continue;		
								 
								if(contaningObj.equals(objStack.get(prevASI).peek())) {
									defObj = contaningObj;
									continue;
								}
								else {
									insertRetPhi=true;
									break;
								}							
							}
							
							RetPhiOSSAInstruction retphi=null;
							if(insertRetPhi) {
								retPhi = createRetPhi(tr, ptsTo,outsideObjInstructions);
								defObj = retPhi.defObj;
							}
														
						}
					}//if(ivinstrctn.hasDef()) {
					
					InvokeOSSAInstruction invokeOSSAInstrctn = new InvokeOSSAInstruction(ivinstrctn, defObj, args, changedArgs, argPhiInstrctns, outsideObjInstructions, retPhi);
					DefUse.storeDef(defObj, invokeOSSAInstrctn);
					for(int i=0;i<noofparameters;i++) {
						HashMap<InstanceKey, OSSAObject>arg = args.get(i);
						if(arg!=null) {
							for(Iterator<OSSAObject>objs = arg.values().iterator();objs.hasNext();) {
								DefUse.storeUse(objs.next(), invokeOSSAInstrctn);
							}
						}
						
					}
					
					
					
					instrMap.put(ivinstrctn,invokeOSSAInstrctn);
					
	        	}//if(instructn instanceof SSAInvokeInstruction ){
	        		        	
	        	
	    	 }//if (instructns[j] != null)
		}//for (int j = start; j <= end; j++) 
		
		//Populate Putphis only pre-fixed asi components
		for(Iterator<ISSABasicBlock> succiterator = cfg.getSuccNodes(bb);succiterator.hasNext();) {
			BasicBlock succ = (BasicBlock) succiterator.next();
			HashMap<InstanceKey, SSAInstruction> allocatedPhis = allocationPhiMap.get(succ);
			if(allocatedPhis==null||allocatedPhis.isEmpty())
				continue;
			for(Iterator<InstanceKey>ASIs = allocatedPhis.keySet().iterator();ASIs.hasNext();) {
				InstanceKey asi = ASIs.next();
				PutPhiOSSAInstruction pphiinstr = ((PutPhiOSSAInstruction)allocatedPhis.get(asi));
				OSSAObject argobj=nullobj;
				if(objStack.containsKey(asi)&&!objStack.get(asi).isEmpty())
					argobj = objStack.get(asi).peek();
				pphiinstr.putPhiArgs.get(asi).put(bb, argobj);
				DefUse.storeUse(argobj, pphiinstr);
			}
			
		}
		
		//Making the recursive call for renaming objects to all children of current basic-block.
		for(Iterator<BasicBlock> domchildren = RDF.dominatorTree().getSuccNodes(bb);domchildren.hasNext();){
			BasicBlock child = domchildren.next();
			renameBody(child);
		}
		
		//DONE  Check Restore stack to older state before getting into this basic block
		for(Iterator<InstanceKey>stackASIs = objStack.keySet().iterator();stackASIs.hasNext();) {
			InstanceKey asi = stackASIs.next();
			OSSAObject oldTop = null;
			if(stackview.containsKey(asi))
				oldTop = stackview.get(asi);
			Stack<OSSAObject>singleObjStack = objStack.get(asi);
			if(oldTop==null) {
				singleObjStack.clear();
			}
			else {
				while(!oldTop.equals(singleObjStack.peek())) {
					singleObjStack.pop();
				}
			}
		}
	}
	
	/**
	 * 
	 * @param concreteType Type of object being created.
	 * @param ptsTo ASIs pointed by this object
	 * @return new OSSAObject
	 * Also updates stacks corresponding to ptsTo set
	 */
	public  OSSAObject createObj(TypeReference concreteType, OrdinalSet<InstanceKey> ptsTo) {
		OSSAObject newObj = new OSSAObject(objectVersionNoNext++, concreteType, ptsTo);
		for(Iterator<InstanceKey>ASIs = ptsTo.iterator();ASIs.hasNext();) {
			InstanceKey asi = ASIs.next();
			if(!objStack.containsKey(asi))
				objStack.put( asi, new Stack<OSSAObject>());
//				asi.getConcreteType().
			
			objStack.get(asi).push(newObj);			
		}
		return newObj;
	}
	/**
	 * creates dphi instructions for putfield instructions.
	 * @param concreteType the type of putfield instruction.
	 * @param ptsTo ASIs which are merged through this dphi.
	 * @return dphi instruction
	 */
	private DPhiOSSAInstruction createDPhi(TypeReference concreteType, OrdinalSet<InstanceKey> ptsTo) {
		HashMap<InstanceKey, OSSAObject> argObjs= new HashMap<InstanceKey, OSSAObject>();
		for(Iterator<InstanceKey>ASIs = ptsTo.iterator();ASIs.hasNext();) {
			InstanceKey asi = ASIs.next();
			argObjs.put(asi, objStack.get(asi).peek());
		}
		OSSAObject defObj = createObj(concreteType, ptsTo);
		DPhiOSSAInstruction  newDPhiInstrctn = new DPhiOSSAInstruction(defObj, argObjs);
		for(Iterator<OSSAObject>argobjs = argObjs.values().iterator();argobjs.hasNext();) {
			OSSAObject argobj = argobjs.next();
			DefUse.storeUse(argobj, newDPhiInstrctn);
		}
		DefUse.storeDef(defObj, newDPhiInstrctn);
		return newDPhiInstrctn;
	}
	
	ArgPhiOSSAInstruction createArgPhi(TypeReference concreteType, OrdinalSet<InstanceKey> ptsTo, HashMap<InstanceKey, OSSAObject>argsObjs) {
		OSSAObject defObj = createObj(concreteType, ptsTo);
		ArgPhiOSSAInstruction newArgPhiInstrctn = new ArgPhiOSSAInstruction(defObj, argsObjs);
		for(Iterator<OSSAObject>argobjs = argsObjs.values().iterator();argobjs.hasNext();) {
			OSSAObject argobj = argobjs.next();
			DefUse.storeUse(argobj, newArgPhiInstrctn);
		}
		DefUse.storeDef(defObj, newArgPhiInstrctn);
		return newArgPhiInstrctn;
	}
	
	RetPhiOSSAInstruction createRetPhi(TypeReference concreteType, OrdinalSet<InstanceKey> ptsTo, LinkedList<OutsideObjOSSAInstruction> outsideObjInstructions) {//, HashMap<InstanceKey, OSSAObject>argsObjs) {
		HashMap<InstanceKey, OSSAObject> argsObjs= new HashMap<InstanceKey, OSSAObject>();
	//	LinkedList<OutsideObjOSSAInstruction> outsideObjInstructions = new LinkedList<OutsideObjOSSAInstruction>();
		for(Iterator<InstanceKey>ASIs = ptsTo.iterator();ASIs.hasNext();) {
			InstanceKey asi = ASIs.next();
			if(!objStack.containsKey(asi)) {
				OSSAObject newObj = new OSSAObject(objectVersionNoNext++, concreteType, ptsTo);
				objStack.put( asi, new Stack<OSSAObject>());
//						asi.getConcreteType().
//				OutsideObjOSSAInstruction outsideObjInstrctn = new OutsideObjOSSAInstruction(newObj);
//				DefUse.storeDef(newObj, outsideObjInstrctn);
//				outsideObjInstructions.add(outsideObjInstrctn);
				objStack.get(asi).push(newObj);	
			}
				argsObjs.put(asi, objStack.get(asi).peek());
			
		}
		OSSAObject defObj = createObj(concreteType, ptsTo);
		RetPhiOSSAInstruction newRetPhiInstrctn = new RetPhiOSSAInstruction(defObj, argsObjs,outsideObjInstructions);
		for(Iterator<OSSAObject>argobjs = argsObjs.values().iterator();argobjs.hasNext();) {
			OSSAObject argobj = argobjs.next();
			DefUse.storeUse(argobj, newRetPhiInstrctn);
		}
		DefUse.storeDef(defObj, newRetPhiInstrctn);
		return newRetPhiInstrctn;
	}

	/**
	 * Helper function to fill in RHS uses in a instructino
	 * @param ptsTo
	 * @return
	 */
	private HashMap<InstanceKey, OSSAObject> getUseObjs(OrdinalSet<InstanceKey>ptsTo){
		HashMap<InstanceKey, OSSAObject> retMap = new HashMap<InstanceKey, OSSAObject>();
		for(Iterator<InstanceKey>ASIs = ptsTo.iterator();ASIs.hasNext();) {
			InstanceKey asi = ASIs.next();
			/*
			 * If ptsto set has asi defined outside thiss function, than we need to create stack and object corresponding to that object.
			 */
			if(asi!=null&&!objStack.containsKey(asi)) {
				OSSAObject outObj =  new OSSAObject(objectVersionNoNext++, asi.getConcreteType().getReference(), asi);
				objStack.put( asi, new Stack<OSSAObject>());
//					asi.getConcreteType().
				
				objStack.get(asi).push(outObj);		
				
				outObj.outSideObj = true;
			}
			retMap.put(asi, objStack.get(asi).peek());
		}
		return retMap;
	}
	
	public String toString() {
		StringBuffer result = new StringBuffer();
		SSAInstruction[] instructions = ir.getInstructions();
		result.append("THE OBJECT SSA:-\n\n");
		result.append(funcossainstrctn);
		for(int i=0; i<=cfg.getMaxNumber();i++) {
			BasicBlock bb =cfg.getBasicBlock(i);
			int start = bb.getFirstInstructionIndex();
			int end = bb.getLastInstructionIndex();
			result.append("BB").append(bb.getNumber());
		    result.append("\n");
		    if(ctrlPhiInstructions.containsKey(bb))
			    for(Iterator<SSAPhiInstruction>ctrlphiinstrctns = ctrlPhiInstructions.get(bb).iterator();ctrlphiinstrctns.hasNext();) {
			    	SSAPhiInstruction ctrlphiinstrctn = ctrlphiinstrctns.next();
			    	result.append("           " + ctrlphiinstrctn.toString()).append("\n");
			    }
		    
		    if(objectPutPhiInstructions.containsKey(bb))
			    for(Iterator<PutPhiOSSAInstruction>putphiinstrctns = objectPutPhiInstructions.get(bb).iterator();putphiinstrctns.hasNext();) {
			    	PutPhiOSSAInstruction putphiinstrctn = putphiinstrctns.next();
			    	result.append("           " + putphiinstrctn.toString()).append("\n");
			    }
		  //Ignore this "if"- for exception blocks in source java code.
		      if (bb instanceof ExceptionHandlerBasicBlock) {
		        ExceptionHandlerBasicBlock ebb = (ExceptionHandlerBasicBlock) bb;
		        SSAGetCaughtExceptionInstruction s = ebb.getCatchInstruction();
		        if (s != null) {
		          result.append("           " + s.toString()).append("\n");
		        } else {
		          result.append("           " + " No catch instruction. Unreachable?\n");
		        }
		      }
		     
		      //print other OSSA instructions from instrMap
		      for (int j = start; j <= end; j++) {
		        
		    	  if (instructions[j] != null) {
		    		  SSAInstruction instructn = instructions[j];
		    		  StringBuffer x;
		    		  if(instrMap.containsKey(instructn)){
		    			  SSAInstruction ossainstructn = instrMap.get(instructn);
		    			  if (ossainstructn instanceof PutFieldOSSAInstruction) {
							PutFieldOSSAInstruction putossainstrctn = (PutFieldOSSAInstruction) ossainstructn;
							x= new StringBuffer(putossainstrctn.dphi+"\n");
							x.append(j + "  "+putossainstrctn.toString());
		    			  }
		    			  else
		    				  x = new StringBuffer(j + "  "+ossainstructn.toString());
		    		  }
		    		  else
		    			  x = new StringBuffer(j + "   "+ instructn.toString());
		    		  StringStuff.padWithSpaces(x, 45);
		    		  result.append(x);
		    		  result.append("\n");
		    	  }
		      }//for (int j = start; j <= end; j++)
		}//for(int i=0; i<=cfg.getMaxNumber();i++)
		
		System.out.println(result);
		
		return result.toString();
	}
	
	/**
	 * ASI--Allocation Site Identifier, basically the pointsTo set.
	 * @param asi1
	 * @param asi2
	 * @return True if both the ordinalsets contaning allocation sites have exactly same elements.
	 */
	public Boolean equateASIs(OrdinalSet<InstanceKey> asi1,OrdinalSet<InstanceKey>asi2){
		Collection<InstanceKey>asi1Collection = OrdinalSet.toCollection(asi1);
		Collection<InstanceKey>asi2Collection = OrdinalSet.toCollection(asi2);
		if(asi1Collection.containsAll(asi2Collection))
			if(asi2Collection.containsAll(asi1Collection))
				return true;
		return false;
	}
	
	/**
	 * contains only normal instructinos and not ssa control phi, pi and ossa dphi ,put-phi instructions.
	 */
	private SSAInstruction [] ossainstructions;
	/**
	 * populates ossainstructions array. contains only normas ossa instructinos and no phis or funcossa instructions.
	 */
	public void populateOSSAInstructions() {
		SSAInstruction [] instructions = ir.getInstructions();
		ossainstructions = new SSAInstruction[instructions.length];
		for(int i=0;i<instructions.length;i++) {
			ossainstructions[i] = instrMap.get(instructions[i]);
		}
	}
	/**
	   * Returns the normal instructions. Does not include {@link SSAPhiInstruction}, {@link SSAPiInstruction}, or
	   * {@link SSAGetCaughtExceptionInstruction}s, or OSSA putphi, dphi instruyctinons which are currently managed by {@link BasicBlock}. Entries in the returned array
	   * might be null.
	   * 
	   * This may go away someday.
	   */
	public SSAInstruction[] getOSSAInstructions() {
	    return ossainstructions;
	  }
	/**
	 * TODO handle the case of phis also.
	 * @param instrctn whose basic-block is to be found out.
	 * @return 
	 */
	
	public BasicBlock getBasicBlockForOSSAInstruction(SSAInstruction instrctn) {
		int i=0;
		/**
		 * if it is funcOssainstructino then return first basicblock
		 */
		if(instrctn.toString().contentEquals(funcossainstrctn.toString()))
			return (BasicBlock)ir.getBasicBlockForInstruction(ir.getInstructions()[0]);
		for(i =0; i<ossainstructions.length;i++) {
			if(ossainstructions[i]==instrctn)
				break;
		}
		assert(i<=ossainstructions.length):"assert error in ObjectSSA";
		if(i==ossainstructions.length)
			return (BasicBlock) ir.getBasicBlockForInstruction(ir.getInstructions()[0]);
		return (BasicBlock) ir.getBasicBlockForInstruction(ir.getInstructions()[i]);
	}
	
	
	
	/**
	 * 
	 * @param ir
	 * @param valueNumber whose type is returned 
	 * @return type of valuenumber in ir.
	 */
	public static TypeAbstraction findType (IR ir, int valueNumber){
		boolean doPrimitives = true; // infer types for primitive vars?
	    TypeInference ti = TypeInference.make(ir, doPrimitives);
	    TypeAbstraction type = ti.getType(valueNumber);
	    return type;
	}
}//class ObjectSSA 
