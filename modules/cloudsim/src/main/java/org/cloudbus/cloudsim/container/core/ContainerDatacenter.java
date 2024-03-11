package org.cloudbus.cloudsim.container.core;


import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.cloudbus.cloudsim.container.resourceAllocators.ContainerAllocationPolicy;
import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by sareh on 10/07/15.
 * Modified by Remo Andreoli (Feb 2024)
 */
public class ContainerDatacenter extends Datacenter {
    /**
     * The container provisioner.
     */
    @Setter
    @Getter
    private ContainerAllocationPolicy containerAllocationPolicy;

    /**
     * The container list.
     */
    private List<? extends Container> containerList;

    /**
     * The scheduling interval.
     */
    private String experimentName;
    /**
     * The log address.
     */
    private String logAddress;


    /**
     * Allocates a new PowerDatacenter object.
     * @param name
     * @param characteristics
     * @param vmAllocationPolicy
     * @param containerAllocationPolicy
     * @param storageList
     * @param schedulingInterval
     * @param experimentName
     * @param logAddress
     * @throws Exception
     */
    public ContainerDatacenter(
            String name,
            DatacenterCharacteristics characteristics,
            VmAllocationPolicy vmAllocationPolicy,
            ContainerAllocationPolicy containerAllocationPolicy,
            List<Storage> storageList,
            double schedulingInterval, String experimentName, String logAddress) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);

        setContainerAllocationPolicy(containerAllocationPolicy);
        setContainerList(new ArrayList<>());
        setExperimentName(experimentName);
        setLogAddress(logAddress);
    }

    /**
     * Overrides this method when making a new and different type of resource. <br>
     * <b>NOTE:</b> You do not need to override {} method, if you use this method.
     *
     * @pre $none
     * @post $none
     */
    protected void registerOtherEntity() {
        // empty. This should be override by a child class
    }

    /**
     * Processes events or services that are available for this PowerDatacenter.
     *
     * @param ev a Sim_event object
     * @pre ev != null
     * @post $none
     */
    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case containerCloudSimTags.CONTAINER_SUBMIT -> processContainerSubmit(ev, true);
            case containerCloudSimTags.CONTAINER_MIGRATE -> processContainerMigrate(ev, false);

            // other (potentially unknown tags) are processed by the base class
            default -> super.processEvent(ev);
        }
    }

    public void processContainerSubmit(SimEvent ev, boolean ack) {
        List<Container> containerList = (List<Container>) ev.getData();

        for (Container container : containerList) {
            boolean result = getContainerAllocationPolicy().allocateVmForContainer(container, getVmList());
            if (ack) {
                int[] data = new int[3];
                data[1] = container.getId();
                if (result) {
                    data[2] = CloudSimTags.TRUE;
                } else {
                    data[2] = CloudSimTags.FALSE;
                }
                if (result) {
                    ContainerVm containerVm = getContainerAllocationPolicy().getContainerVm(container);
                    data[0] = containerVm.getId();
                    if(containerVm.getId() == -1){

                        Log.printConcatLine("The ContainerVM ID is not known (-1) !");
                    }
//                    Log.printConcatLine("Assigning the container#" + container.getUid() + "to VM #" + containerVm.getUid());
                    getContainerList().add(container);
                    if (container.isBeingInstantiated()) {
                        container.setBeingInstantiated(false);
                    }
                    container.updateContainerProcessing(CloudSim.clock(), getContainerAllocationPolicy().getContainerVm(container).getContainerScheduler().getAllocatedMipsForContainer(container));
                } else {
                    data[0] = -1;
                    //notAssigned.add(container);
                    Log.printLine(String.format("Couldn't find a vm to host the container #%s", container.getUid()));

                }
                send(ev.getSource(), CloudSim.getMinTimeBetweenEvents(), containerCloudSimTags.CONTAINER_CREATE_ACK, data);

            }
        }

    }

    /**
     * Process the event for an User/Broker who wants to know the status of a Cloudlet. This
     * PowerDatacenter will then send the status back to the User/Broker.
     *
     * @param ev a Sim_event object
     * @pre ev != null
     * @post $none
     */
    protected void processCloudletStatus(SimEvent ev) {
        int cloudletId = 0;
        int userId = 0;
        int vmId = 0;
        int containerId = 0;
        int status = -1;
        ContainerVm containerVm;

        try {
            // if a sender using cloudletXXX() methods
            int[] data = (int[]) ev.getData();
            cloudletId = data[0];
            userId = data[1];
            vmId = data[2];
            containerId = data[3];
            //Log.printLine("Data Center is processing the cloudletStatus Event ");

            containerVm = (ContainerVm) getVmAllocationPolicy().getHost(vmId, userId).getVm(vmId, userId);
            status = containerVm.getContainer(containerId, userId).getContainerCloudletScheduler().getCloudletStatus(cloudletId);
        }

        // if a sender using normal send() methods
        catch (ClassCastException c) {
            try {
                Cloudlet cl = (Cloudlet) ev.getData();
                cloudletId = cl.getCloudletId();
                userId = cl.getUserId();
                containerId = cl.getContainerId();

                containerVm = (ContainerVm) getVmAllocationPolicy().getHost(vmId, userId).getVm(vmId, userId);
                status = containerVm.getContainer(containerId, userId).getContainerCloudletScheduler().getCloudletStatus(cloudletId);
            } catch (Exception e) {
                Log.printConcatLine(getName(), ": Error in processing CloudSimTags.CLOUDLET_STATUS");
                Log.printLine(e.getMessage());
                return;
            }
        } catch (Exception e) {
            Log.printConcatLine(getName(), ": Error in processing CloudSimTags.CLOUDLET_STATUS");
            Log.printLine(e.getMessage());
            return;
        }

        int[] array = new int[3];
        array[0] = getId();
        array[1] = cloudletId;
        array[2] = status;

        int tag = CloudSimTags.CLOUDLET_STATUS;
        sendNow(userId, tag, array);
    }

    /**
     * Process the event for a User/Broker who wants to create a VM in this PowerDatacenter. This
     * PowerDatacenter will then send the status back to the User/Broker.
     *
     * @param ev  a Sim_event object
     * @param ack the ack
     * @pre ev != null
     * @post $none
     */
    protected void processVmCreate(SimEvent ev, boolean ack) {
        ContainerVm containerVm = (ContainerVm) ev.getData();

        boolean result = getVmAllocationPolicy().allocateHostForVm(containerVm);

        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = containerVm.getId();

            if (result) {
                data[2] = CloudSimTags.TRUE;
            } else {
                data[2] = CloudSimTags.FALSE;
            }
            send(containerVm.getUserId(), CloudSim.getMinTimeBetweenEvents(), CloudSimTags.VM_CREATE_ACK, data);
        }

        if (result) {
            getVmList().add(containerVm);

            if (containerVm.isBeingInstantiated()) {
                containerVm.setBeingInstantiated(false);
            }

            containerVm.updateVmProcessing(CloudSim.clock(), getVmAllocationPolicy().getHost(containerVm).getVmScheduler()
                    .getAllocatedMipsForVm(containerVm));
        }

    }

    /**
     * Process the event for a User/Broker who wants to destroy a VM previously created in this
     * PowerDatacenter. This PowerDatacenter may send, upon request, the status back to the
     * User/Broker.
     *
     * @param ev  a Sim_event object
     * @param ack the ack
     * @pre ev != null
     * @post $none
     */
    protected void processVmDestroy(SimEvent ev, boolean ack) {
        ContainerVm containerVm = (ContainerVm) ev.getData();
        getVmAllocationPolicy().deallocateHostForVm(containerVm);

        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = containerVm.getId();
            data[2] = CloudSimTags.TRUE;

            sendNow(containerVm.getUserId(), CloudSimTags.VM_DESTROY_ACK, data);
        }

        getVmList().remove(containerVm);
    }

    /**
     * Process the event for a User/Broker who wants to migrate a VM. This PowerDatacenter will
     * then send the status back to the User/Broker.
     *
     * @param ev a Sim_event object
     * @pre ev != null
     * @post $none
     */
    protected void processVmMigrate(SimEvent ev, boolean ack) {
        Object tmp = ev.getData();
        if (!(tmp instanceof Map<?, ?>)) {
            throw new ClassCastException("The data object must be Map<String, Object>");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> migrate = (HashMap<String, Object>) tmp;

        ContainerVm containerVm = (ContainerVm) migrate.get("vm");
        Host host = (Host) migrate.get("host");

        getVmAllocationPolicy().deallocateHostForVm(containerVm);
        host.removeMigratingInVm(containerVm);
        boolean result = getVmAllocationPolicy().allocateHostForVm(containerVm, host);
        if (!result) {
            Log.printLine("[Datacenter.processVmMigrate] VM allocation to the destination host failed");
            System.exit(0);
        }

        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = containerVm.getId();

            if (result) {
                data[2] = CloudSimTags.TRUE;
            } else {
                data[2] = CloudSimTags.FALSE;
            }
            sendNow(ev.getSource(), CloudSimTags.VM_CREATE_ACK, data);
        }

        Log.formatLine(
                "%.2f: Migration of VM #%d to Host #%d is completed",
                CloudSim.clock(),
                containerVm.getId(),
                host.getId());
        containerVm.setInMigration(false);
    }

    /**
     * Process the event for a User/Broker who wants to migrate a VM. This PowerDatacenter will
     * then send the status back to the User/Broker.
     *
     * @param ev a Sim_event object
     * @pre ev != null
     * @post $none
     */
    protected void processContainerMigrate(SimEvent ev, boolean ack) {

        Object tmp = ev.getData();
        if (!(tmp instanceof Map<?, ?>)) {
            throw new ClassCastException("The data object must be Map<String, Object>");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> migrate = (HashMap<String, Object>) tmp;

        Container container = (Container) migrate.get("container");
        ContainerVm containerVm = (ContainerVm) migrate.get("vm");

        getContainerAllocationPolicy().deallocateVmForContainer(container);
        if(containerVm.getContainersMigratingIn().contains(container)){
            containerVm.removeMigratingInContainer(container);}
        boolean result = getContainerAllocationPolicy().allocateVmForContainer(container, containerVm);
        if (!result) {
            Log.printLine("[Datacenter.processContainerMigrate]Container allocation to the destination vm failed");
            System.exit(0);
        }
        if (containerVm.isInWaiting()){
            containerVm.setInWaiting(false);

        }

        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = container.getId();

            if (result) {
                data[2] = CloudSimTags.TRUE;
            } else {
                data[2] = CloudSimTags.FALSE;
            }
            sendNow(ev.getSource(), containerCloudSimTags.CONTAINER_CREATE_ACK, data);
        }

        Log.formatLine(
                "%.2f: Migration of container #%d to Vm #%d is completed",
                CloudSim.clock(),
                container.getId(),
                container.getVm().getId());
        container.setInMigration(false);
    }

    /**
     * Processes a Cloudlet based on the event type.
     *
     * @param ev   a Sim_event object
     * @param type event type
     * @pre ev != null
     * @pre type > 0
     * @post $none
     */
    protected void processCloudlet(SimEvent ev, int type) {
        int cloudletId = 0;
        int userId = 0;
        int vmId = 0;
        int containerId = 0;

        try { // if the sender using cloudletXXX() methods
            int[] data = (int[]) ev.getData();
            cloudletId = data[0];
            userId = data[1];
            vmId = data[2];
            containerId = data[3];
        }

        // if the sender using normal send() methods
        catch (ClassCastException c) {
            try {
                Cloudlet cl = (Cloudlet) ev.getData();
                cloudletId = cl.getCloudletId();
                userId = cl.getUserId();
                vmId = cl.getVmId();
                containerId = cl.getContainerId();
            } catch (Exception e) {
                Log.printConcatLine(super.getName(), ": Error in processing Cloudlet");
                Log.printLine(e.getMessage());
                return;
            }
        } catch (Exception e) {
            Log.printConcatLine(super.getName(), ": Error in processing a Cloudlet.");
            Log.printLine(e.getMessage());
            return;
        }

        // begins executing ....
        switch (type) {
            case CloudSimTags.CLOUDLET_CANCEL -> processCloudletCancel(cloudletId, userId, vmId, containerId);
            case CloudSimTags.CLOUDLET_PAUSE -> processCloudletPause(cloudletId, userId, vmId, containerId, false);
            case CloudSimTags.CLOUDLET_PAUSE_ACK -> processCloudletPause(cloudletId, userId, vmId, containerId, true);
            case CloudSimTags.CLOUDLET_RESUME -> processCloudletResume(cloudletId, userId, vmId, containerId, false);
            case CloudSimTags.CLOUDLET_RESUME_ACK -> processCloudletResume(cloudletId, userId, vmId, containerId, true);
            default -> {
            }
        }

    }

    /**
     * Process the event for a User/Broker who wants to move a Cloudlet.
     *
     * @param receivedData information about the migration
     * @param type         event tag
     * @pre receivedData != null
     * @pre type > 0
     * @post $none
     */
    protected void processCloudletMove(int[] receivedData, int type) {
        updateCloudletProcessing();

        int[] array = receivedData;
        int cloudletId = array[0];
        int userId = array[1];
        int vmId = array[2];
        int containerId = array[3];
        int vmDestId = array[4];
        int containerDestId = array[5];
        int destId = array[6];
        ContainerVm containerVm;

        // get the cloudlet
        containerVm = (ContainerVm) getVmAllocationPolicy().getHost(vmId, userId).getVm(vmId, userId);
        Cloudlet cl = containerVm.getContainer(containerId, userId)
                                 .getContainerCloudletScheduler().cloudletCancel(cloudletId);

        boolean failed = false;
        if (cl == null) {// cloudlet doesn't exist
            failed = true;
        } else {
            // has the cloudlet already finished?
            if (cl.getCloudletStatusString().equals("Success")) {// if yes, send it back to user
                int[] data = new int[3];
                data[0] = getId();
                data[1] = cloudletId;
                data[2] = 0;
                sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_SUBMIT_ACK, data);
                sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_RETURN, cl);
            }

            // prepare cloudlet for migration
            cl.setVmId(vmDestId);

            // the cloudlet will migrate from one vm to another does the destination VM exist?
            if (destId == getId()) {
                containerVm = (ContainerVm) getVmAllocationPolicy().getHost(vmDestId, userId).getVm(vmDestId, userId);
                if (containerVm == null) {
                    failed = true;
                } else {
                    // time to transfer the files
                    double fileTransferTime = predictFileTransferTime(cl.getRequiredFiles());
                    containerVm.getContainer(containerDestId, userId).getContainerCloudletScheduler().cloudletSubmit(cl, fileTransferTime);
                }
            } else {// the cloudlet will migrate from one resource to another
                int tag = ((type == CloudSimTags.CLOUDLET_MOVE_ACK) ? CloudSimTags.CLOUDLET_SUBMIT_ACK
                        : CloudSimTags.CLOUDLET_SUBMIT);
                sendNow(destId, tag, cl);
            }
        }

        if (type == CloudSimTags.CLOUDLET_MOVE_ACK) {// send ACK if requested
            int[] data = new int[3];
            data[0] = getId();
            data[1] = cloudletId;
            if (failed) {
                data[2] = 0;
            } else {
                data[2] = 1;
            }
            sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_SUBMIT_ACK, data);
        }
    }

    /**
     * Processes a Cloudlet submission.
     *
     * @param ev  a SimEvent object
     * @param ack an acknowledgement
     * @pre ev != null
     * @post $none
     */
    protected void processCloudletSubmit(SimEvent ev, boolean ack) {
        updateCloudletProcessing();

        try {
            Cloudlet cl = (Cloudlet) ev.getData();

            // checks whether this Cloudlet has finished or not
            if (cl.isFinished()) {
                String name = CloudSim.getEntityName(cl.getUserId());
                Log.printConcatLine(getName(), ": Warning - Cloudlet #", cl.getCloudletId(), " owned by ", name,
                        " is already completed/finished.");
                Log.printLine("Therefore, it is not being executed again");
                Log.printLine();

                // NOTE: If a Cloudlet has finished, then it won't be processed.
                // So, if ack is required, this method sends back a result.
                // If ack is not required, this method don't send back a result.
                // Hence, this might cause CloudSim to be hanged since waiting
                // for this Cloudlet back.
                if (ack) {
                    int[] data = new int[3];
                    data[0] = getId();
                    data[1] = cl.getCloudletId();
                    data[2] = CloudSimTags.FALSE;

                    // unique tag = operation tag
                    int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
                    sendNow(cl.getUserId(), tag, data);
                }

                sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_RETURN, cl);

                return;
            }

            // process this Cloudlet to this CloudResource
            cl.setResourceParameter(getId(), getCharacteristics().getCostPerSecond(), getCharacteristics()
                    .getCostPerBw());

            int userId = cl.getUserId();
            int vmId = cl.getVmId();
            int containerId = cl.getContainerId();

            // time to transfer the files
            double fileTransferTime = predictFileTransferTime(cl.getRequiredFiles());

            Host host = getVmAllocationPolicy().getHost(vmId, userId);
            ContainerVm vm = (ContainerVm) host.getVm(vmId, userId);
            Container container = vm.getContainer(containerId, userId);
            double estimatedFinishTime = container.getContainerCloudletScheduler().cloudletSubmit(cl, fileTransferTime);

            // if this cloudlet is in the exec queue
            if (estimatedFinishTime > 0.0 && !Double.isInfinite(estimatedFinishTime)) {
                estimatedFinishTime += fileTransferTime;
                send(getId(), estimatedFinishTime, CloudSimTags.VM_DATACENTER_EVENT);
            }

            if (ack) {
                int[] data = new int[3];
                data[0] = getId();
                data[1] = cl.getCloudletId();
                data[2] = CloudSimTags.TRUE;

                // unique tag = operation tag
                int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
                sendNow(cl.getUserId(), tag, data);
            }
        } catch (ClassCastException c) {
            Log.printLine(String.format("%s.processCloudletSubmit(): ClassCastException error.", getName()));
            c.printStackTrace();
        } catch (Exception e) {
            Log.printLine(String.format("%s.processCloudletSubmit(): Exception error.", getName()));
            e.printStackTrace();
        }

        checkCloudletCompletion();
    }

    /**
     * Processes a Cloudlet resume request.
     *
     * @param cloudletId resuming cloudlet ID
     * @param userId     ID of the cloudlet's owner
     * @param ack        $true if an ack is requested after operation
     * @param vmId       the vm id
     * @pre $none
     * @post $none
     */
    protected void processCloudletResume(int cloudletId, int userId, int vmId, int containerId, boolean ack) {
        ContainerVm containerVm = (ContainerVm) getVmAllocationPolicy().getHost(vmId, userId).getVm(vmId, userId);
        double eventTime = containerVm.getContainer(containerId, userId)
                                      .getContainerCloudletScheduler().cloudletResume(cloudletId);

        boolean status = false;
        if (eventTime > 0.0) { // if this cloudlet is in the exec queue
            status = true;
            if (eventTime > CloudSim.clock()) {
                schedule(getId(), eventTime, CloudSimTags.VM_DATACENTER_EVENT);
            }
        }

        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = cloudletId;
            if (status) {
                data[2] = CloudSimTags.TRUE;
            } else {
                data[2] = CloudSimTags.FALSE;
            }
            sendNow(userId, CloudSimTags.CLOUDLET_RESUME_ACK, data);
        }
    }

    /**
     * Processes a Cloudlet pause request.
     *
     * @param cloudletId resuming cloudlet ID
     * @param userId     ID of the cloudlet's owner
     * @param ack        $true if an ack is requested after operation
     * @param vmId       the vm id
     * @pre $none
     * @post $none
     */
    protected void processCloudletPause(int cloudletId, int userId, int vmId, int containerId, boolean ack) {
        ContainerVm containerVm = (ContainerVm) getVmAllocationPolicy().getHost(vmId, userId).getVm(vmId, userId);
        boolean status = containerVm.getContainer(containerId, userId)
                .getContainerCloudletScheduler().cloudletPause(cloudletId);

        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = cloudletId;
            if (status) {
                data[2] = CloudSimTags.TRUE;
            } else {
                data[2] = CloudSimTags.FALSE;
            }
            sendNow(userId, CloudSimTags.CLOUDLET_PAUSE_ACK, data);
        }
    }

    /**
     * Processes a Cloudlet cancel request.
     *
     * @param cloudletId resuming cloudlet ID
     * @param userId     ID of the cloudlet's owner
     * @param vmId       the vm id
     * @pre $none
     * @post $none
     */
    protected void processCloudletCancel(int cloudletId, int userId, int vmId, int containerId) {
        ContainerVm containerVm = (ContainerVm) getVmAllocationPolicy().getHost(vmId, userId).getVm(vmId, userId);
        Cloudlet cl = containerVm.getContainer(containerId, userId)
                .getContainerCloudletScheduler().cloudletCancel(cloudletId);
        sendNow(userId, CloudSimTags.CLOUDLET_CANCEL, cl);
    }

    /**
     * Verifies if some cloudlet inside this PowerDatacenter already finished. If yes, send it to
     * the User/Broker
     *
     * @pre $none
     * @post $none
     */
    protected void checkCloudletCompletion() {
        for (Host host : getVmAllocationPolicy().getHostList()) {
            for (ContainerVm vm : host.<ContainerVm>getVmList()) {
                for (Container container : vm.getContainerList()) {
                    while (container.getContainerCloudletScheduler().isFinishedCloudlets()) {
                        Cloudlet cl = container.getContainerCloudletScheduler().getNextFinishedCloudlet();
                        if (cl != null) {
                            sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_RETURN, cl);
                        }
                    }
                }
            }
        }
    }


    public <T extends Container> List<T> getContainerList() {
        return (List<T>) containerList;
    }

    public void setContainerList(List<? extends Container> containerList) {
        this.containerList = containerList;
    }


    public String getExperimentName() {
        return experimentName;
    }

    public void setExperimentName(String experimentName) {
        this.experimentName = experimentName;
    }

    public String getLogAddress() {
        return logAddress;
    }

    public void setLogAddress(String logAddress) {
        this.logAddress = logAddress;
    }
}


