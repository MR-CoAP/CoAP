/**
 * CoAP on Moterunner Demonstration
 * Copyright (c) 2013-2014, SAP AG
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * - Neither the name of the SAP AG nor the names of its contributors may be 
 *   used to endorse or promote products derived from this software without 
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL SAP BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Contributors:
 *   Matthias Thoma
 *   Martin Zabel
 *   Theofilos Kakantousis
 *
 *
 *
 * =====================================
 * CoAP on MoteRunner Demonstration code
 * =====================================
 *
 * The aim of this code is to serve as an example on how to implement the CoAP protool on the Moterunner
 * platform. This code was written with the specifics of an embedded platform in mind, but nonetheless
 * aims for clarity. Therefore this is not the most optimized version possible.
 *
 *
 * Todo:
 * 5. Consider to generalize ACK management (TTL/waitForACK) => Does it make sense to move that to Message or to a
 *    generic CoAP handler class? The ACK management should be more general.
 * 7. Rewrite message sending and add the possiblity to send parts of the replies in the periodicPacketProcessing routine, for that it most likely makes also sense
 *    to refactor out the sending into a own method. Number of messages send per iteration should be configurable (done MT) --> starvation?!
 * 8. Introduce some Asserts (where it makes sense)
 * 9. Write doxygen code (or whatever the GAC wants)
 * 10. Test with the latest 6LoWPAN moterunner version 
 * 11. Write more test cases
 * 12. Test :-)
 */

//##if NOASSERT
//##define ASSERT()
//##else
//##define ASSERT(cond) if( !(cond) ) \
//## handleAssert(csr.s2b(#cond),csr.s2b(__FILE__),__LINE__);
//##endif

package com.sap.coap;

import com.ibm.iris.*;
import com.ibm.saguaro.system.*;
import com.ibm.saguaro.mrv6.*;
import com.sap.coap.Core;
import com.sap.coap.Message;
import com.sap.coap.Request;
import com.sap.coap.ObserveRequest;

//##if LOGGING
import com.ibm.saguaro.logger.*;
import com.sap.research.coap.stubs.Util;
//##endif

public class CoapSensorDemo extends UDPSocket {
	
    //Inter-mote communication message
    private static byte[] header;

    //Timer to periodically process pending packets
    private static Timer timerPeriodicProcessing;
    private static boolean periodicProcessingRunning = false;
    private static int LOCAL_PORT = 1024;

    // Singleton instance
    private static CoapSensorDemo socket = new CoapSensorDemo();
    private static byte temperatureSensorAttached;
    private static SDev sensor;

    //Timer periodic delay for packet processing, See Observe-draft p.14.
    private static int timerDelayPeriodicProcessing = 3000;

    // A list of pending GET requests for temperature
    @Immutable private static byte MAX_PENDING_REQUESTS = 2;
    private static byte numPendingRequests = 0;
    private static Request[] pendingTemperatureRequests;

    // A list of temperature observers
    @Immutable private static byte MAX_OBSERVERS = 2;
    private static byte numObservers = 0;
    private static ObserveRequest[] temperatureObservers;
    private static int lastServedObserver;
    private static int lastObserverServedWithDeprecatedValue;

    // MessageID Header -CoAP- for sending SensorData
    private static short msgID = (short) Util.rand8();
    private static int observeIDs = 0;
    @Immutable private static short SENSOR_READ_INTERVALL = 8000;
    @Immutable private static byte KEEP_SENSOR_ACTIVE_DURATION = 4;

    // Sending operations per round
    @Immutable private static short MAX_SENDING_OPERATIONS_PER_ROUND = 4;

    // payload cache for delayed sending
    private static byte[] payloadCache = new byte[4];
    private static int payloadCacheSize = 4;

    //minimum time between a CON and its retransmission in case no ACK was received
    @Immutable private static int ACK_TIMEOUT = 2000;
    @Immutable private static int ACK_RANDOM_FACTOR = 2; // for easiness

    //maximum time between a CON and its retransmission in case no ACK was received
    @Immutable private static int MAX_RETRANSMIT = 4;

    //gives the interval in seconds how often a CON instead of a NON should be used to ensure the observer still listens
    @Immutable private static int CHECKRATE_FOR_OBSERVER_TIMEOUT = 20000;

    //private static byte[] currentTime;

    //option identifiers
    @Immutable private static int OPTION_NUMBER_URI = 11;
    @Immutable private static int OPTION_NUMBER_OBSERVE = 6;
	@Immutable private static int OPTION_BLOCK2 = 23;
	
	//semantic description
	@Immutable private static int MAX_BODY_SIZE = 64;
	//TODO enter the semantic description
	private static byte[] description = csr.s2b("ABCDEFGHIJKLMNOPQRSTUUUUUVWXY und Z. Unser Alphabet ist das, singt mal mit es macht sehr viel Spass. Eins, zwei, drei, vier Eckstein, alles muss versteckt sein. Hinter mir und vorder mir gilt es nicht, und an beiden Seiten nicht! Eins, zwei, drei, vier, fuenf, sechs, sieben, acht, neun, zehn -ich komme!");
	private static Request pendingDescriptionRequest;

    static {
        temperatureSensorAttached = 0;
        Core.registerResourceString(csr.s2b("</sensor/temp>;if=\"sensor\",</status>"), 36);

        // Initialize queues
        pendingTemperatureRequests = new Request[MAX_PENDING_REQUESTS];
        temperatureObservers = new ObserveRequest[MAX_OBSERVERS];

        // Setup a periodic timer callback for transmissions
        timerPeriodicProcessing = new Timer();
        timerPeriodicProcessing.setCallback(new TimerEvent(null) {
            @Override
            public void invoke(byte param, long time) {
                CoapSensorDemo.periodicPacketProcessing(param, time);
            }
        });
		
        socket.bind(LOCAL_PORT);

        sensor = new SDev();
        Assembly.setSystemInfoCallback(new SystemInfo(null) {
            @Override
            public int invoke(int type, int info) {
                return CoapSensorDemo.onSystemInfo(type, info);
            }
        });
        sensor.setReadHandler(new DevCallback(null) {
            @Override
            public int invoke(int arg0, byte[] arg1, int arg2, int arg3,
                              long arg4) {
                return CoapSensorDemo.onSensorData(arg0, arg1, arg2, arg3, arg4);
            }
        });
    }

    public static int onSystemInfo(int type, int info) {
        if (type == Assembly.SYSEV_DELETED) {
            try {
                if (sensor != null)
                    sensor.close();
            } catch (MoteException e) {
            }
        }
        return 0;
    }


    /**
     *  Receiving UDP Packet and starting the process for sending the actual temperature. Most of the CoAP protocol
     *  logic is implemented as part of this method.
     *
     */

    public int onPacket(Packet packetIn) {
        int packetRCVdstport = packetIn.dstport;
        int packetRCVsrcport = packetIn.srcport;
        byte[] packetRCVsrcAddr = new byte[packetIn.srcaddr.length];
        byte[] packetRCVdstAddr = new byte[packetIn.dstaddr.length];

        Address.copyAddress(packetIn.srcaddr, 0, packetRCVsrcAddr, 0);
        Address.copyAddress(packetIn.dstaddr, 0, packetRCVdstAddr, 0);

        //##if LOGGING
        Logger.appendString(csr.s2b("CoapSensorDemo.onPacket() :: START-"));
        Logger.appendInt(packetRCVsrcport);
        Logger.flush(Mote.INFO);
        //##endif

        // Reading CoAP Data
        // **
        // All CoAP data is in the payload
        int state = 0;
        Message coapMessage = new Message();
		byte decodeResult;

        try {
            decodeResult = coapMessage.decode(packetIn.payloadBuf, packetIn.payloadOff, packetIn.payloadLen);
            packetIn.release();
			
        } catch (MoteException e) {
            //##if LOGGING
            Logger.appendString(csr.s2b("CoAPDecode :: MESSAGE EXCEPTION"));
            Logger.flush(Mote.INFO);
            //##endif
            packetIn.release();
            return 0;  // we can nothing do about that, so just ignore
        }
		
		/* First do a sanity check on the incoming packet. We will only proceed if we have a request.
           All other message types indicate that we got an response. This sample implementation does not
           send out any requests and therefore is not expecting any results.
         */
		
		//only CoAP version 1 supported, all other ignored, see draft-ietf-core-coap-18, 3
		if (coapMessage.getVersion() != 1) {	
            return 0;
        }

		//message could not be parsed
		if (decodeResult == -1) {
			sendErrorCode(packetRCVsrcport, packetRCVsrcAddr, packetRCVdstAddr,  coapMessage, (byte) 4, (byte) 0);
			return 0;
		}

        byte typeClass = (byte) (coapMessage.getCode() >> 5);  // The upper three bits are the class
        if (typeClass != 0) {
            sendErrorCode(packetRCVsrcport, packetRCVsrcAddr, packetRCVdstAddr,  coapMessage, (byte) 5, (byte) 1);
            return 0;
        }

        // We support only GET requests, so check for that
        byte type = coapMessage.getType();
        // We support only GET requests, so check for that
        if (coapMessage.getCode() != Message.GET && type != Message.ACK && type != Message.RST) {
            sendErrorCode(packetRCVsrcport, packetRCVsrcAddr, packetRCVdstAddr, coapMessage, (byte) 4, (byte) 5);
            return 0;
        }
		
		/**
         * ACKs and RST are currently only used for observers, if no ACK is received after a specific amount of
         * time, the observer will be removed from the list of observers. the same happens when a RST is
         * received.
         *
         * RST handling:
         *   for an observer: Remove the observer and do not send any further messages.
         *   any other:  Ignore
         *
         * ACK handling:
         *   for an observer: Remove the cancel the resend confirmed message timer
         *   any other: Ignore
         *
         */
		  
        //##if LOGGING
        Logger.appendString(csr.s2b("CoapSensorDemo.onPacket() :: CHECK FOR ACK "));
        Logger.appendInt(coapMessage.getMessageId());
        Logger.flush(Mote.INFO);
        //##endif
		
		if ((type == Message.ACK) || (type == Message.RST)) {
            //##if LOGGING
            Logger.appendString(csr.s2b("CoapSensorDemo.onPacket() :: ACK OR RST received "));
            Logger.appendInt(coapMessage.getMessageId());
            Logger.flush(Mote.INFO);
            //##endif

            for (int i = 0; i < MAX_OBSERVERS; i++) {
                ObserveRequest observer = temperatureObservers[i];

                if (observer!=null) {
                    int observerMessageId = observer.coapMessage.getMessageId();
                    int msgId = coapMessage.getMessageId();
                    if (coapMessage.getMessageId() == observer.coapMessage.getMessageId()) {
                        if (type == Message.ACK) {
                            observer.resendCONTimer.cancelAlarm();
                            return 0;
                        }

                        if (type == Message.RST) {
                            removeObserver(i);
                            return 0;
                        }
                    }
                }
            }

            // We do not expect ACK or RST further down the road, so leave handling at this point!
            return 0;
        }

         /*
          * We support the following calls and map it to a state
          *
          * State 1: Get sensor information (battery status, URI "status")
          * State 32: Sensor temperature, with get, no observe option (URI "sensor/temp")
          * State 932: Sensor temperature, with get, with observe option (URI "sensor/temp")
          * State 54: standard for resource discovery (".well-known/core")
		  * State 6: Get description
          *
          * The digits in the state represent an URI path fragment, in reverse order of appearance
          * 0 is reserved for non recognized parts
          * 9 is reserved for the additional observe option
          * if there are more than 8 different path fragments, two digits have to identify each of them
          * in order to realise that, change "partNo *= 10;" to "partNo *= 100;"
          */

        int offset = coapMessage.getOffsetOfOptionWithId(OPTION_NUMBER_URI,0); //get the first message part
        int partNo = 1;

        if(offset != -1) {
            do  {
                int fragmentLength = coapMessage.getValueSizeOfOptionWithOffset(offset);
                byte[] fragment = coapMessage.getValueOfOptionWithOffset(offset);

                if(fragmentLength == 6 && Util.compareData(fragment, 0, csr.s2b("status"), 0, 6) == 0xFFFF) {
                    state += 1*partNo;
                }
                else if(fragmentLength == 6 && Util.compareData(fragment, 0, csr.s2b("sensor"), 0, 6) == 0xFFFF) {
                    state += 2*partNo;
                }
                else if(fragmentLength == 4 && Util.compareData(fragment, 0, csr.s2b("temp"), 0, 4) == 0xFFFF) {
                    state += 3*partNo;
                }
                else if(fragmentLength == 11 && Util.compareData(fragment, 0, csr.s2b(".well-known"), 0, 11) == 0xFFFF) {
                    state += 4*partNo;
                }
                else if(fragmentLength == 4 && Util.compareData(fragment, 0, csr.s2b("core"), 0, 4) == 0xFFFF) {
                    state += 5*partNo;
                }
				else if(fragmentLength == 6 && Util.compareData(fragment, 0, csr.s2b("foobar"), 0, 6) == 0xFFFF) {
                    state += 6*partNo;
                }
                //else the current digit of state will be 0.
                //this results in one digit of the path id being 0. theoretically, this can be used as 'wildcard' when matching the URI..

                offset = coapMessage.findOffsetOfNextOption(offset);
                partNo *= 10;
            } while(offset < coapMessage.optionArraySize && coapMessage.idOfOptionWithOffset(offset, OPTION_NUMBER_URI) == OPTION_NUMBER_URI);
        }
		
        if (coapMessage.hasOption(OPTION_NUMBER_OBSERVE)) {
            state += 9*partNo;
        }

        //##if LOGGING
        Logger.appendString(csr.s2b("CoapSensorDemo:: Reached State:  "));
        Logger.appendInt(state);
        Logger.flush(Mote.INFO);
        //##endif


        /**
         * DISPATCHER Code
         */

        boolean handled = false;

        /** STATE 1: Status (battery) information
         *
         */

        if (state == 1) {
            handled = true;

            // Retrieve battery status from Mote
            int batteryStatus = Mote.queryInfo(Mote.BATTERY_STATUS);

            // Either NON or CON. If it is a CON then reply with CON, otherwise with ACK.
            int msgid = coapMessage.getMessageId();

            if (type == coapMessage.CON) {
                type = coapMessage.ACK;
            } else {
                msgid = calculateMsgID();
                type = coapMessage.NON;
            }

            coapMessage.setMessageHeader(type, coapMessage.getTokenLength(), coapMessage.createResponseCode((byte) 2, (byte) 05), msgid);
            coapMessage.clearPayload();
            coapMessage.clearOptions();

            byte[] payload = new byte[10];
            int batteryStatusByteRange = batteryStatus & 0xFF;
            int batteryStatusPercentage = batteryStatusByteRange*2/5;

            //correcting rounding errors
            if(batteryStatusPercentage > 100) {
                batteryStatusPercentage = 100;
            }

            Util.copyData(csr.s2b("%"),0,payload,3,1);
            byte[] utf8String = intToUtf8(batteryStatusPercentage,10,3,-1);
            Util.copyData(utf8String,0,payload,0,3);
            coapMessage.setPayload(payload);

            Packet tempPacket = coapMessage.prepareResponseForSourcePacket(packetRCVsrcport, packetRCVsrcAddr, packetRCVdstAddr, LOCAL_PORT);
            socket.send(tempPacket);
            return 0;
        }

        /** STATE 932: Get with observe option enabled
         *
         */

        if (state == 932) {
            handled = true;

            // Check whether this is a register or unregister
            byte[] observeOptionValue = null;

            observeOptionValue = coapMessage.valueOfOptionWithId(6,0);

            // this needs to be 0 or 1. If it is not zero or one we have a protocol error, but earlier versions
            // CoAP observe did not had this distinction. For now, just register if it has no value set.

            if (observeOptionValue==null) {
                return 0;
            }

            if ((observeOptionValue.length > 0) && (observeOptionValue[0] == 1))  { // unregister
                for (int i = 0; i < MAX_OBSERVERS; i++) {
                    // todo: check where the request comes from the same origin
                    if (temperatureObservers[i] == null) {
                        if (temperatureObservers[i].coapMessage.getTokenLength()==coapMessage.getTokenLength()) {
                            if (Util.compareData(temperatureObservers[i].coapMessage.token, 0, coapMessage.token,0, coapMessage.getTokenLength())==0xFFFF) {
                                removeObserver(i);
                                return 0;
                            }
                        }
                    }
                }
            }


            // Find a free spot in the observe queue. In case there is no free space, reply with a simple GET and
            // ignore the observe option

            boolean success = false;

            for (int i = 0; i < MAX_OBSERVERS; i++) {
                if (temperatureObservers[i] == null) {
                    success = true;
                    temperatureObservers[i] = new ObserveRequest(packetRCVdstAddr, packetRCVdstport, packetRCVsrcAddr, packetRCVsrcport, coapMessage, true, false);

                    numObservers++;

                    //set the timer to periodically check whether the observer still listens
                    temperatureObservers[i].nextCONTimer = new Timer();
                    temperatureObservers[i].nextCONTimer.setCallback(new TimerEvent(null) {
                        @Override
                        public void invoke(byte param, long time) {
                            nextCON(param, time);
                        }
                    });
                    temperatureObservers[i].nextCONTimer.setParam((byte) i);
                    temperatureObservers[i].nextCONTimer.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, CHECKRATE_FOR_OBSERVER_TIMEOUT));

                    //initialize the timer to resend confirmable messages after a certain time span
                    temperatureObservers[i].resendCONTimer = new Timer();
                    temperatureObservers[i].resendCONTimer.setCallback(new TimerEvent(null) {
                        public void invoke(byte param, long time) {
                            resendCON(param, time);
                        }
                    });
                    temperatureObservers[i].resendCONTimer.setParam((byte) i);

                    if (!periodicProcessingRunning)
                        timerPeriodicProcessing.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, 10));

                    break;
                }
            }
            //if no more observer can be added, send response for GET, according to draft-ietf-core-observe-13, sect. 7
            if (!success) {
                state = 32;
                while(coapMessage.hasOption(OPTION_NUMBER_OBSERVE)) {
                    coapMessage.removeOptionWithId(OPTION_NUMBER_OBSERVE,0);
                }
            }
        }
        /* STATE 32:  Retrieve Temperature without observe option
         */

        if (state == 32) {
            handled = true;
            // find a spot in the request queue
            boolean success = false;

            for (int i = 0; i < MAX_PENDING_REQUESTS; i++) {
                if (pendingTemperatureRequests[i] == null) {
                    pendingTemperatureRequests[i] = new Request(packetRCVdstAddr, packetRCVdstport, packetRCVsrcAddr, packetRCVsrcport, coapMessage, true, true);
                    success = true;
                    numPendingRequests++;
                    break;
                }
            }

            if (!success) {
                sendErrorCode(packetRCVsrcport, packetRCVsrcAddr, packetRCVdstAddr, coapMessage, (byte) 5, (byte) 3);
                return 0;
            }

            if (!periodicProcessingRunning)
                timerPeriodicProcessing.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, 10));

        }
        //TODO: state == 12 (.well-known)
		
		if(state == 6) {
			handled = true;

			if (pendingDescriptionRequest == null) {
				pendingDescriptionRequest = new Request(packetRCVdstAddr, packetRCVdstport, packetRCVsrcAddr, packetRCVsrcport, coapMessage, true, false);
				numPendingRequests++;
				if (!periodicProcessingRunning)
					timerPeriodicProcessing.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, 10));
			}
			else {
				sendErrorCode(packetRCVsrcport, packetRCVsrcAddr, packetRCVdstAddr, coapMessage, (byte) 5, (byte) 3);
                return 0;
            }
		}

        // Nothing recognized => 4.04 method not found
        if (!handled) {
            sendErrorCode(packetRCVsrcport, packetRCVsrcAddr, packetRCVdstAddr, coapMessage, (byte) 4, (byte) 4);
            return 0;
        }

        return 0;

    }

    /**
     *  periodicPacketProcessing is running every timerDelayPeriodicProcessing milliseconds and is trying to send out
     *  new sensor reading or do bookkeeping.
     *
     * @param param
     * @param time
     */

    public static void periodicPacketProcessing(byte param, long time) {
        boolean shouldSensorBeActive = (numObservers != 0) || (numPendingRequests != 0);
        periodicProcessingRunning = shouldSensorBeActive || (temperatureSensorAttached > 1);

        if(periodicProcessingRunning) {
            timerPeriodicProcessing.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, timerDelayPeriodicProcessing));
        }

        if (shouldSensorBeActive) {
            if(temperatureSensorAttached == 0) {
                temperatureSensorAttached = KEEP_SENSOR_ACTIVE_DURATION;
                sensor.open(IRIS.DID_MTS400_HUMID_TEMP, null, 0, 0);
                sensor.read(Device.ASAP,4,0);
            }
            doSending();
        } else {
            if (temperatureSensorAttached == 1) {
                try {
                    sensor.setReadHandler(null);
                    if (sensor != null) {
                        sensor.close();
                    }
                } catch (MoteException e) {}
            }
            if (temperatureSensorAttached > 0) {
                temperatureSensorAttached--;
            }
        }
    }

    public void sendErrorCode(int inDstPort, byte[] inDstAddr, byte[] inSrcAddr, Message msg, byte codeClass, byte codeID) {
        byte tokenLen = msg.getTokenLength();
        // Either NON or CON. If it is a CON then reply with CON, otherwise with ACK.
        int msgid = msg.getMessageId();

        byte type = msg.getType();
        if (type == Message.CON) {
            type = Message.ACK;
        } else {
            msgid = calculateMsgID();
            type = Message.NON;
        }

        msg.setMessageHeader(type, tokenLen, Message.createResponseCode((byte) codeClass, (byte) codeID), msgid);
        msg.clearPayload();
        msg.clearOptions();

        int port = inDstPort;
        int lenp = msg.getMessageLength();

        Packet tempPacket = Mac.getPacket();
        tempPacket.release();
        Address.copyAddress(inDstAddr, 0, tempPacket.dstaddr, 0);
        Address.copyAddress(inSrcAddr, 0, tempPacket.srcaddr, 0);
        tempPacket.create(port, LOCAL_PORT, lenp);

        msg.encodeTo(tempPacket.payloadBuf, tempPacket.payloadOff);
        socket.send(tempPacket);
    }


    private static void removeObserver(int idx) {
        if (temperatureObservers[idx] != null) {
            temperatureObservers[idx].resendCONTimer.cancelAlarm();
            temperatureObservers[idx].nextCONTimer.cancelAlarm();
            temperatureObservers[idx] = null;
            numObservers--;
        }
    }


    private static int doSending() {
        if (numPendingRequests==0 && numObservers==0)
            return 0; // Quick exit, nothing to do

        int sendOperationsLeft = MAX_SENDING_OPERATIONS_PER_ROUND; // we will not send more than than MAX_SENDING_OPERATIONS_PER_ROUND number of packets.

		//answer pending request for the description
		//to allow a long description text, draft-ietf-core-block-14 for block-wise transfer of payloads ("block2 option") is implemented
		if(pendingDescriptionRequest != null) {
			Message coapMessage = pendingDescriptionRequest.coapMessage;
			int msgid = coapMessage.getMessageId();
			byte type = coapMessage.getType();

			if (type == coapMessage.CON) {
				type = coapMessage.ACK;
			} else {
				msgid = calculateMsgID();
				type = coapMessage.NON;
			}
			
			byte[] value = null;
			
			// yet, a block number as long is overdimensioned, since the description array cannot be larger than an int can represent. 
			// However, until the actual data copying, this is kept as general as possible
			long blockNumber = 0;
			int blockSize = 0;
			long startOffset = 0;
			
			boolean success = true;
			boolean useDefaultBlockSize = false;

			//read request 
			int optionOffset = coapMessage.getOffsetOfOptionWithId(OPTION_BLOCK2, 0);
			value = coapMessage.getValueOfOptionWithOffset(optionOffset);

			if(value == null || value.length == 0) 
				useDefaultBlockSize = true;
			else {	
				int szx = 0x07&value[value.length-1];
				if(szx > 6)
					success = false;
				else {
					blockNumber = (0xF0&value[value.length-1]) >>> 4;
					if(value.length > 1) 
						blockNumber += 16*((int)value[value.length-2]);
					if(value.length > 2)
						blockNumber += 16*256*((int)value[0]);
						
					blockSize = 16 << szx;  //equals 2**(SZX + 4) from spec
					if(blockSize > 64) 
						useDefaultBlockSize = true;
					startOffset = blockNumber << (szx + 4); //equals blockSize*blockNumber
					
					//send general error if an out-of-bounds segment is requested. Current blocks draft mentions the necessity of a new response code
					if(startOffset > description.length)
						success = false;
					//if the desired fragmentation has a too large packet size, use own maximum as fallback (DA_PDU_MAX_SIZE defines for a network packet 128 byte as maximum, up to 64 byte for the CoAP body seem to work fine)
					if(blockSize > MAX_BODY_SIZE)
						useDefaultBlockSize = true;
				}
			}
			if(useDefaultBlockSize) {
				blockSize = 32;
				blockNumber = 0;	
				//byte is set to: blockNumber 0, more flag will be set later, szx 1
				value = new byte[1];
				value[0] = 0x01;
				startOffset = 0;
			}
			if(success) {
				//prepare body
				long endOffset = startOffset+blockSize;
				if(description.length < endOffset) {	 //aah. TODO. Any Math.min available?
					endOffset = description.length;
				}
				int bodySize = ((int)(endOffset-startOffset));
				
				byte[] body = new byte[bodySize];
				Util.copyData(description, (int)startOffset, body, 0, (int)bodySize);
				
				//set the more flag, the 5th bit of the last byte in the option value
				if(endOffset == description.length) { //nothing more follows, set bit to 0
					value[value.length-1] &= (byte)0xF7;
				}
				else {								//more follows, set to 1
					value[value.length-1] |= (byte)0x08;
				}
				
				//set option and message body
				coapMessage.insertOption(23, value, value.length);
				coapMessage.setPayload(body);
				coapMessage.setMessageHeader(type, coapMessage.getTokenLength(), coapMessage.createResponseCode((byte) 2, (byte) 05), msgid);
			}
			else {
				coapMessage.setMessageHeader(type, coapMessage.getTokenLength(), coapMessage.createResponseCode((byte) 4, (byte) 00), msgid); //TODO clear options?
			}
			
			//send response
			Packet tempPacket = pendingDescriptionRequest.prepareResponsePacketForRequest(coapMessage.getMessageLength(), LOCAL_PORT);
			coapMessage.encodeTo(tempPacket.payloadBuf, tempPacket.payloadOff);
			socket.send(tempPacket);
			pendingDescriptionRequest = null;
			numPendingRequests--;
			sendOperationsLeft--;
		}
		
        // Send reply to all waiting clients, we do this first and then try to update the observers.
        for (int i = 0; i < MAX_PENDING_REQUESTS; i++) {
			if (sendOperationsLeft==0)
                return 1;
            if (pendingTemperatureRequests[i] != null) {
                // Either NON or CON. If it is a CON then reply with CON, otherwise with ACK.
                Message coapMessage = pendingTemperatureRequests[i].coapMessage;
                int msgid = coapMessage.getMessageId();

                byte type = coapMessage.getType();

                // A con request gets an ACK and the original message id, all other replies have to generate
                // a message id

                if (type == coapMessage.CON) {
                    type = coapMessage.ACK;
                } else {
                    msgid = calculateMsgID();
                    type = coapMessage.NON;
                }

                coapMessage.clearPayload();
                coapMessage.clearOptions();

                boolean send = false;

                // todo: Verstehe nicht, was das macht...
                if(Util.compareData(payloadCache, 0, new byte[payloadCacheSize], 0, payloadCacheSize) == 0xFFFF) { //Why does it crash with payloadCacheSize instead of 4?
                    if(coapMessage.roundCounter == 3) {
                        coapMessage.setMessageHeader(type, coapMessage.getTokenLength(), coapMessage.createResponseCode((byte) 5, (byte) 03), msgid);
                        send = true;
                    }
                    else {
                        coapMessage.roundCounter++;
                    }
                }
                else {
                    coapMessage.setMessageHeader(type, coapMessage.getTokenLength(), coapMessage.createResponseCode((byte) 2, (byte) 05), msgid);
                    coapMessage.setPayload(payloadCache);
                    send = true;
                }

                if(send) {
                    Packet tempPacket = pendingTemperatureRequests[i].prepareResponsePacketForRequest(coapMessage.getMessageLength(), LOCAL_PORT);
                    coapMessage.encodeTo(tempPacket.payloadBuf, tempPacket.payloadOff);
                    socket.send(tempPacket);
                    pendingTemperatureRequests[i] = null;
                    numPendingRequests--;
                    sendOperationsLeft--;
                }
            }
        }

        // Send reply to all waiting observers

        int i = lastServedObserver;
        do {
            i = (i+1) % MAX_OBSERVERS;
            //check whether all observers already have the latest value
            if (temperatureObservers[i] != null) {
                Message coapMessage;
                byte type;

				//create a new confirmable message on regular base, but not if the last one is still in retransmission
                if(temperatureObservers[i].nextMessageAsCON && temperatureObservers[i].retransmissionCounter == 0) {
                    type = Message.CON;
                    //the request's coapMessage variable should always store the latest confirmable message (and not the message with the current temperature value, as one might expect) to resend it if no ACK was received in a certain time span
                    coapMessage = temperatureObservers[i].coapMessage;
                    coapMessage.clearPayload();
                    coapMessage.clearOptions();
					
                    temperatureObservers[i].resendCONTimer.cancelAlarm();

                    int random = (int) (Time.currentTicks() % ((ACK_TIMEOUT * ACK_RANDOM_FACTOR) - ACK_TIMEOUT));
                    temperatureObservers[i].currentRetransmissionMillis = ACK_TIMEOUT+random;
                    temperatureObservers[i].retransmissionCounter = 0;

                    temperatureObservers[i].resendCONTimer.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, temperatureObservers[i].currentRetransmissionMillis));
                }
                else {
                    //a freshly created non-confirmable message is sent, the stored message remains untouched
                    type = Message.NON;

                    coapMessage = new Message();
                    Util.copyData(temperatureObservers[i].coapMessage.header,0,coapMessage.header,0,4);
                    coapMessage.token = new byte[temperatureObservers[i].coapMessage.getTokenLength()];
                    Util.copyData(temperatureObservers[i].coapMessage.token,0,coapMessage.token,0,temperatureObservers[i].coapMessage.getTokenLength());
                }
				
				temperatureObservers[i].nextMessageAsCON = false;

                coapMessage.setMessageHeader(type, coapMessage.getTokenLength(), Message.createResponseCode((byte) 2, (byte) 05), calculateMsgID());

                byte[] observeID = new byte[2];
                Util.set16be(observeID, 0, observeIDs);
                coapMessage.insertOption(OPTION_NUMBER_OBSERVE, observeID, 2);
                observeIDs++;

                coapMessage.setPayload(payloadCache);

                //##if LOGGING
                Logger.appendString(csr.s2b("CoapSensorDemo.onSensorData() :: Sending observation"));
                Logger.appendInt(observeIDs);
                Logger.flush(Mote.INFO);
                //##endif

                Packet tempPacket = temperatureObservers[i].prepareResponsePacketForRequest(coapMessage.getMessageLength(), LOCAL_PORT);
                coapMessage.encodeTo(tempPacket.payloadBuf, tempPacket.payloadOff);

                socket.send(tempPacket);
                sendOperationsLeft--;

                if (sendOperationsLeft==0)
                    return 1;
            }
            lastServedObserver = i;

            if (i == lastObserverServedWithDeprecatedValue)
                return 0;
        } while(i != lastServedObserver);

        return 0;
    }

    public static int onSensorData(int flags, byte[] data, int len, int info, long time) {
        //##if LOGGING
//        Logger.appendString(csr.s2b("CoapSensorDemo.onSensorData() :: START"));
//        Logger.flush(Mote.INFO);
        //##endif


        if ((flags & Device.FLAG_FAILED) != 0) {
            // Reading the sensor failed in some way. Data is broken.
            //##if LOGGING
            Logger.appendString(csr.s2b("CoapSensorDemo.onSensorData() :: **** SENSOR FAILED ****"));
            Logger.flush(Mote.INFO);
            //##endif
            return -1;
        }

        // Create nice looking result

        // Get temperature in format
        // yyy.xx
        // this is done by moving it left two digits (*100) applying the formula (* 0.01 - 40) resulting in
        // temp - 4000

        long temperature = (Util.get16be(data,2) & 0x3FFF) - 4000;

        byte formattedTemperature[] = intToUtf8((int) temperature,10,5,2);

        payloadCache = new byte[formattedTemperature.length];
        Util.copyData(formattedTemperature, 0, payloadCache, 0, formattedTemperature.length);
        payloadCacheSize = formattedTemperature.length;

//##if LOGGING
//        Logger.appendString(csr.s2b("CoapSensorDemo.onSensorData() :: FINISH"));
//        Logger.flush(Mote.INFO);
//##endif
        sensor.read(Device.TIMED, 4, Time.currentTicks()+Time.toTickSpan(Time.MILLISECS, SENSOR_READ_INTERVALL));

        lastObserverServedWithDeprecatedValue = lastServedObserver;

        return 0;
    }

    private static short calculateMsgID() {
        if (msgID<32767)
            msgID++;
        else
            msgID=0;

        return msgID;
    }

    public static byte getTokenLength() {
        return (byte) (header[0] & 0x0F);
    }


    public void onEvent(int ev, long para0) {
        LED.setState((byte) 0, (byte) 1);

        if (ev == Mac.EV_PARENT_LOST) {
            timerPeriodicProcessing.cancelAlarm();

            // Close device when node gets lost
            temperatureSensorAttached = 0;
            try {
                if (sensor != null) {
                    sensor.close();
                }
            } catch (MoteException e) {
            }
        }
        if (temperatureSensorAttached == 0) {
            return;
        }
    }

    public static void nextCON(byte param, long time) {
        int position = (int) param;
        temperatureObservers[position].nextMessageAsCON = true;
        temperatureObservers[position].nextCONTimer.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, CHECKRATE_FOR_OBSERVER_TIMEOUT));
    }

    //this is called if and only if no ACK was received for the last transmission of the CON message
    public static void resendCON(byte param, long time) {
        int position = (int) param;
        temperatureObservers[position].retransmissionCounter++;

        if(temperatureObservers[position].retransmissionCounter > MAX_RETRANSMIT) {
            //if the waiting time limit is reached
            removeObserver(position);
        }
        else {
            //resend the confirmable message, with an exponentially growing waiting time for an answer, according to draft-ietf-core-coap-18, sect 4.2
			temperatureObservers[position].currentRetransmissionMillis *= 2;
            Message coapMessage = temperatureObservers[position].coapMessage;
            Packet tempPacket = temperatureObservers[position].prepareResponsePacketForRequest(coapMessage.getMessageLength(), LOCAL_PORT);
            coapMessage.encodeTo(tempPacket.payloadBuf, tempPacket.payloadOff);
            socket.send(tempPacket);
            temperatureObservers[position].resendCONTimer.setAlarmBySpan(Time.toTickSpan(Time.MILLISECS, temperatureObservers[position].currentRetransmissionMillis));
        }
    }


    /**
     * Returns a given number as a nicely formatted UTF8 string. This is a helper function that created nice looking
     * payload, for example, for JSON clients.
     *
     * @param number  The number to be converted
     * @param base    The target base. Need to be between 2 (binary) and 16 (hexadecimal)
     * @param digits  Number of digits. Missing digits will be filled with spaces
     * @param decimalSeperatorPosition Position of the decimal separator from the right.
     * @return
     */
    private static byte[] intToUtf8(int number, int base, int digits, int decimalSeperatorPosition) {
        byte[] lookup = csr.s2b("0123456789abcdef");
        byte sign = 0;

        int position = digits;

        if (number<0) {
            position++;
            sign=1;
            number *= -1;
        }

        if (decimalSeperatorPosition>=0 && decimalSeperatorPosition<digits)
            position++;
        else
            decimalSeperatorPosition = -1;

        byte[] result = new byte[position];

        if(base < 2 || base > 16) {
            return result;  // unsupported base, ignore
        }


        if (number<0)
            return result;


        while(number != 0 && digits != 0) {
            position--;
            digits--;
            result[position] = lookup[number%base];
            number /= base;

            decimalSeperatorPosition--;
            if (decimalSeperatorPosition==0) {
                position--;
                if (position>=0)
                    result[position]='.';
            }
        }

        if(number != 0)
            return result; // not enough digits for number


        while(position > 0) {
            position--;
            result[position] = csr.s2b(" ")[0];
        }

        if (sign==1) result[0] = '-';

        return result;

    }
}