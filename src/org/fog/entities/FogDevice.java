package org.fog.entities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;
import org.fog.utils.GeoCoverage;

public class FogDevice extends Datacenter {
	private static boolean PRINTING_ENABLED = false;
	private static void print(String msg){
		if(PRINTING_ENABLED)System.out.println(CloudSim.clock()+" : "+msg);
	}
	private static double RESOURCE_USAGE_VECTOR_SIZE = 100;
	private Queue<Tuple> outgoingTupleQueue;
	private boolean isOutputLinkBusy;
	private double uplinkBandwidth;
	private double latency;
	private List<String> activeApplications;
	
	private Map<String, Application> applicationMap;
	private Map<String, List<String>> appToModulesMap;
	private GeoCoverage geoCoverage;
	
	protected Map<String, Queue<Double>> utilization; 
	
	protected Map<Integer, Integer> cloudTrafficMap;
	
	protected double lockTime;
	
	/**	
	 * ID of the parent Fog Device
	 */
	protected int parentId;
	
	/**
	 * ID of the Controller
	 */
	private int controllerId;
	/**
	 * IDs of the children Fog devices
	 */
	private List<Integer> childrenIds;

	private Map<Integer, List<String>> childToOperatorsMap;
	
	public FogDevice(
			String name, 
			GeoCoverage geoCoverage,
			FogDeviceCharacteristics characteristics,
			VmAllocationPolicy vmAllocationPolicy,
			List<Storage> storageList,
			double schedulingInterval,
			double uplinkBandwidth, double latency) throws Exception {
		super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
		setGeoCoverage(geoCoverage);
		setCharacteristics(characteristics);
		setVmAllocationPolicy(vmAllocationPolicy);
		setLastProcessTime(0.0);
		setStorageList(storageList);
		setVmList(new ArrayList<Vm>());
		setSchedulingInterval(schedulingInterval);
		setUplinkBandwidth(uplinkBandwidth);
		setLatency(latency);
		for (Host host : getCharacteristics().getHostList()) {
			host.setDatacenter(this);
		}
		setActiveApplications(new ArrayList<String>());
		// If this resource doesn't have any PEs then no useful at all
		if (getCharacteristics().getNumberOfPes() == 0) {
			throw new Exception(super.getName()
					+ " : Error - this entity has no PEs. Therefore, can't process any Cloudlets.");
		}

		// stores id of this class
		getCharacteristics().setId(super.getId());
		
		applicationMap = new HashMap<String, Application>();
		appToModulesMap = new HashMap<String, List<String>>();
		outgoingTupleQueue = new LinkedList<Tuple>();
		setOutputLinkBusy(false);
		
		this.utilization = new HashMap<String, Queue<Double>>();
		
		setChildrenIds(new ArrayList<Integer>());
		setChildToOperatorsMap(new HashMap<Integer, List<String>>());
		
		this.cloudTrafficMap = new HashMap<Integer, Integer>();
		
		this.lockTime = 0;
	}

	/**
	 * Overrides this method when making a new and different type of resource. <br>
	 * <b>NOTE:</b> You do not need to override {@link #body()} method, if you use this method.
	 * 
	 * @pre $none
	 * @post $none
	 */
	protected void registerOtherEntity() {
		//updateResourceUsage();
		//performAdativeReplacement(null);
	}
	
	@Override
	protected void processOtherEvent(SimEvent ev) {
		switch(ev.getTag()){
		case FogEvents.TUPLE_ARRIVAL:
			processTupleArrival(ev);
			break;
		case FogEvents.LAUNCH_MODULE:
			processModuleArrival(ev);
			break;
		case FogEvents.RELEASE_OPERATOR:
			processOperatorRelease(ev);
			break;
		case FogEvents.SENSOR_JOINED:
			processSensorJoining(ev);
			break;
		case FogEvents.APP_SUBMIT:
			processAppSubmit(ev);
			break;
		case FogEvents.UPDATE_TUPLE_QUEUE:
			updateTupleQueue();
			break;
		case FogEvents.ACTIVE_APP_UPDATE:
			updateActiveApplications(ev);
			break;
		default:
			break;
		}
	}
	
	/**
	 * Returns the child fog devices concerned for the application appId
	 * @param appId
	 * @return
	 */
	protected List<Integer> childIdsForApplication(String appId){
		List<Integer> childIdsForApplication = new ArrayList<Integer>();
		GeoCoverage geo = getApplicationMap().get(appId).getGeoCoverage();
		for(Integer childId : getChildrenIds()){
			if(((FogDevice)CloudSim.getEntity(childId)).getGeoCoverage().covers(geo) || geo.covers(((FogDevice)CloudSim.getEntity(childId)).getGeoCoverage()))
				childIdsForApplication.add(childId);
		}
		return childIdsForApplication;
	}
	
	private void updateActiveApplications(SimEvent ev) {
		Application app = (Application)ev.getData();
		getActiveApplications().add(app.getAppId());
	}

	/**
	 * Calculates utilization of each operator.
	 * @param operatorName
	 * @return
	 */
	public double getUtilizationOfOperator(String operatorName){
		double total = 0;
		for(Double d : utilization.get(operatorName)){
			total += d;
		}
		return total/utilization.get(operatorName).size();
	}
	
	public String getOperatorName(int vmId){
		for(Vm vm : this.getHost().getVmList()){
			if(vm.getId() == vmId)
				return ((AppModule)vm).getName();
		}
		return null;
	}
	
	protected void checkCloudletCompletion() {
		
		boolean cloudletCompleted = false;
		List<? extends Host> list = getVmAllocationPolicy().getHostList();
		for (int i = 0; i < list.size(); i++) {
			Host host = list.get(i);
			for (Vm vm : host.getVmList()) {
				while (vm.getCloudletScheduler().isFinishedCloudlets()) {
					Cloudlet cl = vm.getCloudletScheduler().getNextFinishedCloudlet();
					if (cl != null) {
						
						cloudletCompleted = true;
						Tuple tuple = (Tuple)cl;
						Application application = getApplicationMap().get(tuple.getAppId());
						
						List<Tuple> resultantTuples = application.getResultantTuples(tuple.getDestModuleName(), tuple);
						for(Tuple resTuple : resultantTuples){
							sendToSelf(resTuple);
						}
							
						
						sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_RETURN, cl);
					}
				}
			}
		}
		if(cloudletCompleted)
			updateAllocatedMips(null);
	}
	
	private void updateAllocatedMips(String incomingOperator){
		getHost().getVmScheduler().deallocatePesForAllVms();
		for(final Vm vm : getHost().getVmList()){
			if(vm.getCloudletScheduler().runningCloudlets() > 0 || ((AppModule)vm).getName().equals(incomingOperator)){
				getHost().getVmScheduler().allocatePesForVm(vm, new ArrayList<Double>(){
					private static final long serialVersionUID = 1L;
				{add((double) getHost().getTotalMips());}});
			}else{
				getHost().getVmScheduler().allocatePesForVm(vm, new ArrayList<Double>(){
					private static final long serialVersionUID = 1L;
				{add(0.0);}});
			}
		}
		for(final Vm vm : getHost().getVmList()){
			AppModule operator = (AppModule)vm;
			operator.updateVmProcessing(CloudSim.clock(), getVmAllocationPolicy().getHost(operator).getVmScheduler()
					.getAllocatedMipsForVm(operator));
		}
	}
	
	private void updateUtils(){
		for(Vm vm : getHost().getVmList()){
			AppModule operator = (AppModule)vm;
			if(utilization.get(operator.getName()).size() > RESOURCE_USAGE_VECTOR_SIZE){
				utilization.get(operator.getName()).remove();
			}
			utilization.get(operator.getName()).add(operator.getTotalUtilizationOfCpu(CloudSim.clock()));
		}
	}
	
	private void processAppSubmit(SimEvent ev) {
		Application app = (Application)ev.getData();
		applicationMap.put(app.getAppId(), app);
	}

	private void displayAllocatedMipsForOperators(){
		System.out.println("-----------------------------------------");
		for(Vm vm : getHost().getVmList()){
			AppModule operator = (AppModule)vm;
			System.out.println("Allocated MIPS for "+operator.getName()+" : "+getHost().getVmScheduler().getTotalAllocatedMipsForVm(operator));
		}
		System.out.println("-----------------------------------------");
	}
	
	private void addChild(int childId){
		if(CloudSim.getEntityName(childId).contains("sensor"))
			return;
		if(!getChildrenIds().contains(childId) && childId != getId())
			getChildrenIds().add(childId);
		if(!getChildToOperatorsMap().containsKey(childId))
			getChildToOperatorsMap().put(childId, new ArrayList<String>());
	}
	
	private void updateCloudTraffic(){
		int time = (int)CloudSim.clock()/1000;
		if(!cloudTrafficMap.containsKey(time))
			cloudTrafficMap.put(time, 0);
		cloudTrafficMap.put(time, cloudTrafficMap.get(time)+1);
	}
	
	private void processTupleArrival(SimEvent ev){
		if(getName().equals("cloud")){
			updateCloudTraffic();
		}
		Tuple tuple = (Tuple)ev.getData();
		//System.out.println(CloudSim.clock()+" : "+getName()+" : has received a tuple = "+tuple.getActualTupleId());
		send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);
		addChild(ev.getSource());
		if(FogUtils.appIdToGeoCoverageMap.containsKey(tuple.getAppId())){
			GeoCoverage geo = FogUtils.appIdToGeoCoverageMap.get(tuple.getAppId());
			if(!(getGeoCoverage().covers(geo) || geo.covers(geoCoverage)))
				return;
		}
		
		if(getHost().getVmList().size() > 0){
			final AppModule operator = (AppModule)getHost().getVmList().get(0);
			if(CloudSim.clock() > 100){
				getHost().getVmScheduler().deallocatePesForVm(operator);
				getHost().getVmScheduler().allocatePesForVm(operator, new ArrayList<Double>(){
					private static final long serialVersionUID = 1L;
				{add((double) getHost().getTotalMips());}});
			}
		}
		
		if(getName().equals("cloud") && tuple.getDestModuleName()==null){
			sendNow(getControllerId(), FogEvents.TUPLE_FINISHED, null);
		}
		
		if(appToModulesMap.containsKey(tuple.getAppId())){
			if(appToModulesMap.get(tuple.getAppId()).contains(tuple.getDestModuleName())){
				int vmId = -1;
				for(Vm vm : getHost().getVmList()){
					if(((AppModule)vm).getName().equals(tuple.getDestModuleName()))
						vmId = vm.getId();
				}
				if(vmId < 0){
					return;
				}
				tuple.setVmId(vmId);
				executeTuple(ev, tuple.getDestModuleName());
			}else if(tuple.getDestModuleName()!=null){
				sendUp(tuple);
			}else{
				sendUp(tuple);
			}
		}else{
			sendUp(tuple);
		}
	}

	private void processSensorJoining(SimEvent ev){
		//TODO Process sensor joining
		send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ACK);
	}
	
	private void executeTuple(SimEvent ev, String operatorId){
		updateAllocatedMips(operatorId);
		processCloudletSubmit(ev, false);
		updateAllocatedMips(operatorId);
	}
	
	private void processModuleArrival(SimEvent ev){
		AppModule module = (AppModule)ev.getData();
		String appId = module.getAppId();
		if(!appToModulesMap.containsKey(appId)){
			appToModulesMap.put(appId, new ArrayList<String>());
		}
		appToModulesMap.get(appId).add(module.getName());
		getVmList().add(module);
		if (module.isBeingInstantiated()) {
			module.setBeingInstantiated(false);
		}
		utilization.put(module.getName(), new LinkedList<Double>());
		module.updateVmProcessing(CloudSim.clock(), getVmAllocationPolicy().getHost(module).getVmScheduler()
				.getAllocatedMipsForVm(module));
	}
	
	private void processOperatorRelease(SimEvent ev){
		this.processVmMigrate(ev, false);
	}
	
	public boolean isAncestorOf(FogDevice dev){
		if(this.geoCoverage.covers(dev.getGeoCoverage()))
			return true;
		return false;
	}
	
	private void updateTupleQueue(){
		if(!getOutgoingTupleQueue().isEmpty()){
			Tuple tuple = getOutgoingTupleQueue().poll();
			sendUpFreeLink(tuple);
		}else{
			setOutputLinkBusy(false);
		}
	}
	
	protected void sendUpFreeLink(Tuple tuple){
		double networkDelay = tuple.getCloudletFileSize()/getUplinkBandwidth();
		setOutputLinkBusy(true);
		send(getId(), networkDelay, FogEvents.UPDATE_TUPLE_QUEUE);
		send(parentId, networkDelay+latency, FogEvents.TUPLE_ARRIVAL, tuple);
	}
	
	protected void sendUp(Tuple tuple){
		if(parentId > 0){
			if(!isOutputLinkBusy()){
				sendUpFreeLink(tuple);
			}else{
				outgoingTupleQueue.add(tuple);
			}
		}
	}
	
	private void sendToSelf(Tuple tuple){
		send(getId(), CloudSim.getMinTimeBetweenEvents(), FogEvents.TUPLE_ARRIVAL, tuple);
	}
	public Host getHost(){
		return getHostList().get(0);
	}
	public int getParentId() {
		return parentId;
	}
	public void setParentId(int parentId) {
		this.parentId = parentId;
	}
	public List<Integer> getChildrenIds() {
		return childrenIds;
	}
	public void setChildrenIds(List<Integer> childrenIds) {
		this.childrenIds = childrenIds;
	}
	public GeoCoverage getGeoCoverage() {
		return geoCoverage;
	}
	public void setGeoCoverage(GeoCoverage geoCoverage) {
		this.geoCoverage = geoCoverage;
	}
	public double getUplinkBandwidth() {
		return uplinkBandwidth;
	}
	public void setUplinkBandwidth(double uplinkBandwidth) {
		this.uplinkBandwidth = uplinkBandwidth;
	}
	public double getLatency() {
		return latency;
	}
	public void setLatency(double latency) {
		this.latency = latency;
	}
	public Queue<Tuple> getOutgoingTupleQueue() {
		return outgoingTupleQueue;
	}
	public void setOutgoingTupleQueue(Queue<Tuple> outgoingTupleQueue) {
		this.outgoingTupleQueue = outgoingTupleQueue;
	}
	public boolean isOutputLinkBusy() {
		return isOutputLinkBusy;
	}
	public void setOutputLinkBusy(boolean isOutputLinkBusy) {
		this.isOutputLinkBusy = isOutputLinkBusy;
	}
	public int getControllerId() {
		return controllerId;
	}
	public void setControllerId(int controllerId) {
		this.controllerId = controllerId;
	}
	public List<String> getActiveApplications() {
		return activeApplications;
	}
	public void setActiveApplications(List<String> activeApplications) {
		this.activeApplications = activeApplications;
	}
	public Map<Integer, List<String>> getChildToOperatorsMap() {
		return childToOperatorsMap;
	}
	public void setChildToOperatorsMap(Map<Integer, List<String>> childToOperatorsMap) {
		this.childToOperatorsMap = childToOperatorsMap;
	}

	public Map<String, Application> getApplicationMap() {
		return applicationMap;
	}

	public void setApplicationMap(Map<String, Application> applicationMap) {
		this.applicationMap = applicationMap;
	}
}