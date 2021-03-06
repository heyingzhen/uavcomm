package com.yuji.uav.comm.mav;

import com.MAVLink.Messages.MAVLinkMessage;
import com.MAVLink.Messages.ardupilotmega.*;
import com.MAVLink.Messages.enums.MAV_COMPONENT;
import com.MAVLink.Messages.enums.MAV_DATA_STREAM;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import org.junit.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * A Junit test that verifies MAVLink communication via google guava EventBus.
 *
 * @author Philip Giacalone
 */
public class MAVLinkBusTest implements MAVCommunicator {

    //properties object populated at runtime from the properties file
    private Properties props;

    //properties file name
    private final static String PROPERTIES_FILE_NAME = "MAVLinkBusTest.properties";

    //the mavLinkCommunicationBus used for mavlink communication
    private static MAVLinkCommunicationBus bus;

    //the following set of fields are populated at runtime from the properties file
    private static byte mavlinkSystemId;        //e.g., 1 for the autopilot
    private static byte mavlinkComponentId;     //e.g., 1 for all components
    private static byte mavlinkStreamId;        //e.g., 1 for MAV_DATA_STREAM_RAW_SENSORS
    private static int mavlinkMessageId;        //e.g., 27 for RAW_IMU
    private static short initialMessageRate;    //e.g., 1 messages/sec
    private static short finalMessageRate;      //e.g., 10 messages/sec
    private static int messageArrivalTimeSecs;  //e.g., 5 seconds
    private static int commandRepeats;          //e.g., 2

    //map that holds the total count of arriving mavlink messages, keyed by the message ID
    private static Map<Integer,Integer> initialCountMap;
    //map that holds the total count of arriving mavlink messages, keyed by the message ID
    private static Map<Integer,Integer> finalCountMap;

    //flag that is set to true once we'r ready to begin counting messages
    private static boolean messageCountingOn = false;
    //flag that is set to true after the initial set of messages have arrived so we can know when to begin counting the final set
    private static boolean initialRateFlag = false;

    //these are ALL of the stream types defined by MAVLink (note that each stream type has an associated set of individual message types)
    private static final int[] STREAM_TYPES = {
            MAV_DATA_STREAM.MAV_DATA_STREAM_RAW_SENSORS,
            MAV_DATA_STREAM.MAV_DATA_STREAM_EXTENDED_STATUS,
            MAV_DATA_STREAM.MAV_DATA_STREAM_RC_CHANNELS,
            MAV_DATA_STREAM.MAV_DATA_STREAM_RAW_CONTROLLER,
            MAV_DATA_STREAM.MAV_DATA_STREAM_POSITION,
            MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA1,
            MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA2,
            MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA3};

    private static final int HEARTBEAT_MSG_ID = 0;

    //key names used in the properties file
    private final static String MAVLINK_SYSID = "MAVLINK_SYSID";
    private final static String MAV_COMP_ID = "MAV_COMP_ID";
    private final static String MAVLINK_STREAM_ID = "MAVLINK_STREAM_ID";
    private final static String MAVLINK_MESSAGE_ID = "MAVLINK_MESSAGE_ID";
    private final static String MAVLINK_INITIAL_MESSAGE_RATE = "MAVLINK_INITIAL_MESSAGE_RATE";
    private final static String MAVLINK_FINAL_MESSAGE_RATE = "MAVLINK_FINAL_MESSAGE_RATE";
    private final static String MAVLINK_MESSAGE_ARRIVAL_TIME_SECS = "MAVLINK_MESSAGE_ARRIVAL_TIME_SECS";
    private final static String MAVLINK_COMMAND_REPEATS = "MAVLINK_COMMAND_REPEATS";

    private final static int START = 1;
    private final static int STOP = 0;

    /**
     * Implementation of MAVCommunicator
     * This message receives MAVLink messages arriving from the UAV
     * @param messageFromUav
     */
    @Override
    //GOTCHA: Methods annotated with @Subscribe MUST be public
    @Subscribe  //MAVLinkMessage
    @AllowConcurrentEvents
    public void receive(MAVTelemetryMessage messageFromUav) {
        if (messageFromUav != null && messageFromUav.getMavLinkMessage() != null){
            MAVLinkMessage mavLinkMessage = messageFromUav.getMavLinkMessage();
            int id = mavLinkMessage.msgid;

            //needed for junit testing only
            //count the individual message types and track each by msgId
            if (this.messageCountingOn){
                if (this.initialRateFlag){
                    this.trackInitialCounts(id);
                } else {
                    this.trackFinalCounts(id);
                }
            }

            //example showing how different arriving messages types can be dispatched for handling
            switch(id) {
                case msg_attitude.MAVLINK_MSG_ID_ATTITUDE:  //#30
                    msg_attitude att = (msg_attitude)mavLinkMessage;
                    //do something with the arriving message
                    break;
                case msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT:    //#33
                    msg_global_position_int pos = (msg_global_position_int)mavLinkMessage;
                    //do something with the arriving message
                    break;
                case msg_ahrs.MAVLINK_MSG_ID_AHRS:  //#163
                    msg_ahrs ahrs = (msg_ahrs)mavLinkMessage;
                    //do something with the arriving message
                    break;
                case msg_sensor_offsets.MAVLINK_MSG_ID_SENSOR_OFFSETS:  //#150
                    //do something with the arriving message
                    break;
//                case etc:
//                    //do something with the arriving message
//                    break;
                default:
                    //we're not handling this message type
                    //just log or print a message
//                    System.out.println("An unhandled MAVLink message arrived, MSGID=" + mavLinkMessage.msgid);
                    break;
            }
        }
    }

    /**
     * Implementation of MAVCommunicator
     * This method sends the given MAVLink message to the UAV
     * @param messageToUav
     */
    @Override
    public void send(MAVCommandMessage messageToUav) {
        bus.postEvent(messageToUav);
    }

    /**
     * Implementation of MAVCommunicator
     * This method sends the given MAVLink message to the UAV
     * @param messageToUav
     * @return
     * @throws MAVLinkSerialPortException
     */
    public void send(MAVCommandMessage messageToUav, int repeats) throws MAVLinkSerialPortException, InterruptedException {
        for (int i=0; i<repeats; i++){
            this.bus.postEvent(messageToUav);
            //guarantees a small delay between sends
            Thread.sleep(200);
        }
    }

    @org.junit.Before
    public void setUp() throws Exception {
        System.out.println("^^^^^^^^^^^^^SETUP CALLED^^^^^^^^^^^^^");

        props = readPropertiesFile(PROPERTIES_FILE_NAME);
        SerialPortSettings serialPortSettings = new SerialPortSettings(props);

        boolean asynchronous = true;
        bus = new MAVLinkCommunicationBus(asynchronous, serialPortSettings);
        bus.registerSubscriber(this);

        mavlinkSystemId = Byte.parseByte(props.getProperty(MAVLINK_SYSID));
        mavlinkComponentId = Byte.parseByte(props.getProperty(MAV_COMP_ID));
        mavlinkStreamId = Byte.parseByte(props.getProperty(MAVLINK_STREAM_ID));
        mavlinkMessageId = Integer.parseInt(props.getProperty(MAVLINK_MESSAGE_ID));
        initialMessageRate = Short.parseShort(props.getProperty(MAVLINK_INITIAL_MESSAGE_RATE));
        finalMessageRate = Short.parseShort(props.getProperty(MAVLINK_FINAL_MESSAGE_RATE));
        messageArrivalTimeSecs = Integer.parseInt(props.getProperty(MAVLINK_MESSAGE_ARRIVAL_TIME_SECS));
        commandRepeats = Integer.parseInt(props.getProperty(MAVLINK_COMMAND_REPEATS));

        System.out.println("properties=" + props);
        System.out.println("^^^^^^^^^^^^^SETUP DONE^^^^^^^^^^^^^");
    }

    @org.junit.After
    public void tearDown() throws Exception {
        if (bus != null){
            bus.closeSerialPort();
        }
    }

    /**
     * Sends a MAVLink command (msg_request_data_stream) to the autopilot to change the message rate
     *
     * @param startStop
     * @param targetSystemId
     * @param targetComponentId
     * @param targetStreamId
     * @param messageRate
     * @return
     * @throws MAVLinkSerialPortException
     * @throws InterruptedException
     */
    private void changeMessageRate(int targetSystemId, int targetComponentId, int targetStreamId, int messageRate, int startStop)
            throws MAVLinkSerialPortException, InterruptedException {
        msg_request_data_stream command = new msg_request_data_stream();
        command.start_stop = (byte)startStop;               //start=1, stop=0
        command.target_system = (byte)targetSystemId;       //match to value set by the system (e.g., a Pixhawk setting)
        command.target_component = (byte)targetComponentId; //apparently ignored by Pixhawk/APM and UDB/MatrixPilot
        command.req_stream_id = (byte)targetStreamId;       //ID of requested data stream
        command.req_message_rate = (short)messageRate;      //messages/second

//        System.out.println("0) SENDING: msg_request_data_stream=" + cmd);
        //send the command several times, just to be sure it goes thru
        //TODO figure out how to get a command acknowledgement from the autopilot
        MAVCommandMessage cmd = new MAVCommandMessage(command);
        this.send(cmd, commandRepeats);
//        System.out.println("0) SENT, result=" + result);
    }

    /**
     * Iterates thru all the pre-defined streamIds and turns off the message rates
     *
     * @throws MAVLinkSerialPortException
     * @throws InterruptedException
     */
    private void stopAllMessages() throws MAVLinkSerialPortException, InterruptedException {
        for (int streamId : STREAM_TYPES){
            changeMessageRate(mavlinkSystemId, MAV_COMPONENT.MAV_COMP_ID_ALL, streamId, 0, STOP);
        }
    }


    /**
     * Sets the ATTITUDE and GLOBAL_POSITION_INT message rates to 19 and 21 messages/sec respectively
     *
     * @throws MAVLinkSerialPortException
     * @throws InterruptedException
     */
    private void resetToDesiredDefaultMessageRates() throws MAVLinkSerialPortException, InterruptedException {
        changeMessageRate(mavlinkSystemId, mavlinkComponentId, MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA1, 19, START);
        changeMessageRate(mavlinkSystemId, mavlinkComponentId, MAV_DATA_STREAM.MAV_DATA_STREAM_POSITION, 21, START);
    }

    /**
     * JUnit test that sends commands to the autopilot and verifies
     * that they were acted upon by the autopilot. In the process,
     * it receives telemetry from the autopilot, thereby verifying
     * both MAVLink commanding and MAVLink telemetry (i.e., both send and receive).
     *
     * @throws Exception
     */
    @org.junit.Test
    public void testMavlink() throws Exception {

        //============ STOP all the streams before we start testing ============
        stopAllMessages();
        Thread.sleep(1000);

        for (int streamId : STREAM_TYPES){
            boolean result = false;
            this.messageCountingOn = false;
            this.initialRateFlag = true;

            System.out.println("============== StreamId {" + streamId + "} ==============");

            //now we're ready to start counting so create the new counters for this streamId
            MAVLinkBusTest.initialCountMap = new HashMap<Integer, Integer>();
            MAVLinkBusTest.finalCountMap = new HashMap<Integer, Integer>();

            //============ SET the initial/slower rate of this stream ============
            changeMessageRate(mavlinkSystemId, mavlinkComponentId, streamId, initialMessageRate, START);
            messageCountingOn = true;
            this.delay(messageArrivalTimeSecs); //delay while we receive and count messages arriving via method receive() above
            messageCountingOn = false;

            //============ set the final/faster rate of this stream ============
            changeMessageRate(mavlinkSystemId, mavlinkComponentId, streamId, finalMessageRate, START);
            this.initialRateFlag = false;
            messageCountingOn = true;
            this.delay(messageArrivalTimeSecs); //delay while we receive and count messages arriving via method receive() above
            messageCountingOn = false;

            //============ stop this stream ============
            changeMessageRate(mavlinkSystemId, mavlinkComponentId, streamId, 0, STOP);


            //now verify that the final rate is higher than the initial rate
            Set<Integer> initialKeys = MAVLinkBusTest.initialCountMap.keySet();
            Set<Integer> finalKeys = MAVLinkBusTest.finalCountMap.keySet();
            System.out.println("initial message counts (msgId=count): " + MAVLinkBusTest.initialCountMap);
            System.out.println("final message counts   (msgId=count): " + MAVLinkBusTest.finalCountMap);
//            System.out.println("initialKeys: " + initialKeys);
//            System.out.println("finalKeys  : " + finalKeys);
            for (Integer key : initialKeys){
                //skip certain messages, since they are always delivered at 1 message/second
                if (key == msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT){
                    continue;
                } else if (key == msg_sensor_offsets.MAVLINK_MSG_ID_SENSOR_OFFSETS){
                    continue;
                }
//                System.out.print("msgId=" + key + ": ");
                int initialCount = MAVLinkBusTest.initialCountMap.get(key);
                int finalCount = MAVLinkBusTest.finalCountMap.get(key);
//                System.out.println("{streamId/msgId}: {" + streamId + "/" + key + "}, initialCount/finalCount {"  + initialCount + "/" + finalCount + "}");
                Assert.assertTrue("ERROR: initialCount should be smaller than finalCount for {streamId/msgId}: {" + streamId + "/" + key + "} " + initialCount + " vs " + finalCount, initialCount < finalCount);
            }
        }

        stopAllMessages();
        Thread.sleep(1000);

        this.resetToDesiredDefaultMessageRates();
    }

    private void printDot(){
        System.out.print(".");
    }
    private void linebreak(){
        System.out.println();
    }

    /**
     * Sleeps for the given number of seconds
     * @param seconds
     * @throws InterruptedException
     */
    private void delay(int seconds) throws InterruptedException {
//        System.out.println("------------- waiting for " + seconds + " seconds --------------");
        System.out.print("Sleeping for " + seconds + " seconds while messages arrive ");
        for (int i=0; i < seconds; i++){
            printDot();
            Thread.sleep(1000);
        }
        linebreak();
//        System.out.println("------------- done sleeping --------------");
    }

    private void trackInitialCounts(int messageId) {

        if (initialCountMap.get(messageId) == null) {
            initialCountMap.put(messageId, 1);
        } else {
            Integer count = initialCountMap.get(messageId);
            count++;
            initialCountMap.put(messageId, count);
        }
    }

    private void trackFinalCounts(int messageId) {

        if (finalCountMap.get(messageId) == null) {
            finalCountMap.put(messageId, 1);
        } else {
            Integer count = finalCountMap.get(messageId);
            count++;
            finalCountMap.put(messageId, count);
        }
    }

    /**
     * Helper method that returns the string representation of a map
     * with optional sorting by key.
     */
    private String toString(Map<Integer,Integer> map, boolean sort) {
        StringBuilder sb = new StringBuilder();
        Set keys = map.keySet();
        Iterator iter = keys.iterator();
        List keynames = new ArrayList();
        while (iter.hasNext()){
            Object key = iter.next();
            keynames.add(key);
        }
        if (sort){
            Collections.sort(keynames);
        }

        iter = keynames.iterator();
        while (iter.hasNext()){
            Object key = iter.next();
            Object value = map.get(key);
            sb.append(key).append("=").append(value).append(", ");
        }
        return sb.toString();
    }

    /**
     * Helper method to read and return the values from a properties file
     * @return a Properties object populated from the give file
     * @throws java.io.IOException
     */
    private Properties readPropertiesFile(String fileName) throws IOException {
        Properties props = new Properties();
        InputStream is = ClassLoader.getSystemResourceAsStream(fileName);
        props.load(is);
        return props;
    }

}
