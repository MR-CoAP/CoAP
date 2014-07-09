/* CoAP on Moterunner Demonstration
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
 *  The following things need to be added before any public release:
 * 3. Consider to add TTL / Resend stuff (see hellosensor.java)
 * 4. Have a look at rules for message id and consider to move it either here or to some generic class
 */

package com.sap.coap;

import com.ibm.saguaro.system.*;
import com.ibm.iris.*;
import com.ibm.saguaro.mrv6.*;

//##if LOGGING
import com.ibm.saguaro.logger.*;
//##endif

public class Message {
    public byte[] header = new byte[4];
    public byte[] token = null;
    public byte[] payload = null;
    public int payloadLength = 0;
    public byte[] options;
    public int optionArraySize = 0;
    public int roundCounter = 0;

    @Immutable public static final byte CON = 0x00;
    @Immutable public static final byte NON = 0x01;
    @Immutable public static final byte ACK = 0x02;
    @Immutable public static final byte RST = 0x03;

    @Immutable public static final byte EMPTY  = 0x00;
    @Immutable public static final byte GET  = 0x01;
    @Immutable public static final byte POST = 0x02;
    @Immutable public static final byte PUT  = 0x03;
    @Immutable public static final byte DELETE = 0x04;

    public final void setPayload(byte[] mypayload){
        this.payload = mypayload;
        this.payloadLength = mypayload.length;
    }

    public Message() {
        header[0] = 0x40; // set the version number
    }

    /// <summary>
    /// Constructor.
    /// </summary>
    /// <param name="type"> something </param>
    /// <param name="tokenLen"> token legnth </param>
    /// <param name="code"> CoAP message code  </param>
    /// <param name="msgid"> CoAP message id </param>

    public Message(byte type, byte tokenLen, byte code, int msgid) {
        setMessageHeader(type, tokenLen, code, msgid);
    }

    public final void setMessageHeader(byte type, byte tokenLen, byte code, int msgid) {
        header[0] = (byte) ((0x40 | (type << 4)) | tokenLen);
        header[1] = code;
        Util.set16be(header,2,msgid);
    }

    public static byte createResponseCode(final byte cl, final byte cc) {
        return (byte) ((cl << 5)  | cc);
    }

    public final byte getVersion() {
        return  (byte) ((header[0] >>> 6) & 0x03);
    }

    public final byte getType() {
        return (byte) ((header[0] & 0x30) >> 4);
    }

    public final void setType(final byte type) {
        byte tl = (byte) (header [0] & 0x0F);
        header[0] = (byte) ((0x40 | (type << 4)) | tl); // set the version number
    }

    public final byte getTokenLength() {
        return (byte) (header[0] & 0x0F);
    }

    public final byte getCode() {
        return header[1];
    }

    public final int getMessageId() {
        return Util.get16be(header,2);
    }

    public final void clearOptions() {
        options=null;
        optionArraySize=0;
    }

    public final void clearPayload() {
        payloadLength = 0;
        payload = null;
    }

    public final byte[] getPayload() {
        return this.payload;
    }

    public final int getPayloadSize() {
        return this.payloadLength;
    }

    public byte[] getURIfromOptionArray() {
        int partNo = 0;
        int bufferOffset = 0;
        int offset = getOffsetOfOptionWithId(11, partNo);

        if (offset<0)
            return null;

        int bufferSize = 0;

        // Calculate buffer size
        int firstOffset = offset;

        while (offset>=0) {
            if (partNo>0)
                bufferSize++;

            int valueSize = getValueSizeOfOptionWithOffset(offset);
            bufferSize += valueSize;
            partNo++;
            offset = getOffsetOfOptionWithId(11, partNo);
        }

        byte[] buffer = new byte[bufferSize];

        partNo=0;
        offset = getOffsetOfOptionWithId(11, partNo);
        int valueSize = getValueSizeOfOptionWithOffset(offset);
        byte[] data = getValueOfOptionWithOffset(offset);

        while (data != null) {
            if (partNo>0) {
                buffer[bufferOffset]='/';
                bufferOffset++;
            }
            partNo++;
            Util.copyData(data, 0, buffer,   bufferOffset, valueSize);
            bufferOffset += valueSize;

            offset = getOffsetOfOptionWithId(11, partNo);
            data = null;

            if (offset>=0) {
                valueSize = getValueSizeOfOptionWithOffset(offset);
                data = getValueOfOptionWithOffset(offset);
            }
        }

        return buffer;
    }

    public boolean hasOption(int id) {
        return (getOffsetOfOptionWithId(id,0) != -1);
    }

    public void insertOption(int id, byte[] value, int valueSize) {
        //1. find position
        // an ascending order of the options has to be kept
        // find start offsets of the 'enclosing' options left and right of the one to insert
        // special case: inserting at the beginning: offsetRightOption = 0
        // special case: inserting at the end: offsetRightOption = optionArraySize (i.e. points behind the array)
        int offsetRightOption = 0;
        int idRightOption = 0;
        int idLeftOption = 0;

        while(offsetRightOption < optionArraySize) { //if the loop is not left by a break, the option has to be inserted at the end
            idRightOption = idOfOptionWithOffset(options, offsetRightOption, idRightOption);
            if(idRightOption > id) {   //insertion point found
                break;
            }
            idLeftOption = idRightOption;
            offsetRightOption = findOffsetOfNextOption(options, offsetRightOption);
        }


        //2. calculate value length field size for this option
        int optionExtendedLengthFieldSize = getExtendedOptionFieldSizeFor(valueSize);

        //3. calculate delta value for this option.
        // depends on the previous id (being 0 when no previous option exists)
        int delta = id - idLeftOption;

        //4. calculate delta field size for this option
        int optionExtendedDeltaFieldSize = getExtendedOptionFieldSizeFor(delta);

        //5. recalculate the delta field size for the next option
        // the delta value for the next option decreases due to the insertion
        // this may result in less bytes being used for the size field
        int deltaFieldSizeRightOption = 0;
        int deltaFieldSizeRightOptionNew = 0;
        int deltaRightOptionNew = 0;
        int extendedDeltaFieldSizeDifferenceRightOption = 0;
        //only if a next option exists
        if(offsetRightOption != optionArraySize) {
            //get the old field size for the next option
            deltaFieldSizeRightOption = optionExtendedDeltaFieldSize(options, offsetRightOption);
            //recalculate delta field size for next option
            deltaRightOptionNew = idRightOption - id;
            deltaFieldSizeRightOptionNew = getExtendedOptionFieldSizeFor(deltaRightOptionNew);
            //determine the size difference between the new and the old field
            extendedDeltaFieldSizeDifferenceRightOption = deltaFieldSizeRightOption - deltaFieldSizeRightOptionNew;
        }

        //7. calculate total size of new option array
        int optionArraySizeNew = optionArraySize
                + 1
                + optionExtendedLengthFieldSize
                + optionExtendedDeltaFieldSize
                + valueSize
                - extendedDeltaFieldSizeDifferenceRightOption;

        //8. allocate mem for new option array
        byte[] optionsNew = new byte[optionArraySizeNew];

        //9. copy options until insertion point to new array
        if(offsetRightOption>0) {
            Util.copyData(options, 0, optionsNew, 0, offsetRightOption);
        }

        int currentOffset = offsetRightOption;	//next position to read from the old options where no additional option is present. points now to the header byte of the next option
        int offsetFirstByte = offsetRightOption;	//points to the header byte of the option to insert
        int currentOffsetNew = offsetFirstByte+1;	//next position to write in the new array (after the header byte of the option to insert)

        //10. write delta
        if(optionExtendedDeltaFieldSize == 1) {
            optionsNew[offsetFirstByte] += 13 << 4;
            optionsNew[currentOffsetNew] = (byte)(delta-13);
        }
        else if(optionExtendedDeltaFieldSize == 2) {
            optionsNew[offsetFirstByte] += 14 << 4;
            Util.set16(optionsNew, currentOffsetNew, delta-269);
        }
        else { //optionExtendedDeltaFieldSize == 0
            optionsNew[offsetFirstByte] += delta << 4;
        }
        currentOffsetNew += optionExtendedDeltaFieldSize;

        //11. write value length
        if(optionExtendedLengthFieldSize == 1) {
            optionsNew[offsetFirstByte] += 13;
            optionsNew[currentOffsetNew] = (byte)(valueSize-13);
        }
        else if(optionExtendedLengthFieldSize == 2) {
            optionsNew[offsetFirstByte] += 14;
            Util.set16(optionsNew, currentOffsetNew, valueSize-269);
        }
        else { //optionExtendedLengthFieldSize == 0
            optionsNew[offsetFirstByte] += valueSize;
        }
        currentOffsetNew += optionExtendedLengthFieldSize;

        //12. copy value
        if(valueSize>0) {
            Util.copyData(value, 0, optionsNew, currentOffsetNew, valueSize);
        }
        currentOffsetNew += valueSize;

        //only if a next option exists
        if(offsetRightOption != optionArraySize) {
            //13. write header of next option with adjusted delta
            //length stays constant, delta is erased
            optionsNew[currentOffsetNew] = (byte) (options[currentOffset] & 0x0F);

            //write recalculated delta to the next option
            if(deltaFieldSizeRightOptionNew == 1) {
                optionsNew[currentOffsetNew] += 13 << 4;
                optionsNew[currentOffsetNew+1] = (byte) (deltaRightOptionNew-13);
            }
            else if(deltaFieldSizeRightOptionNew == 2){
                optionsNew[currentOffsetNew] += 14 << 4;
                Util.set16(optionsNew, currentOffsetNew+1, deltaRightOptionNew-269);
            }
            else { //deltaFieldSizeRightOptionNew == 0
                optionsNew[currentOffsetNew] += deltaRightOptionNew << 4;
            }

            //jump behind the next option's extended delta field delta in the new array
            currentOffsetNew += 1+deltaFieldSizeRightOptionNew;
            //jump behind the next option's extended delta field in the old array
            currentOffset += 1+deltaFieldSizeRightOption;

            //14. copy rest of array (= next option's extended value length field, next option's value, all subsequent options)
            int restLength = optionArraySize - currentOffset;
            Util.copyData(options, currentOffset, optionsNew, currentOffsetNew, restLength);
        }

        //15. replace old options by new
        options = optionsNew;
        optionArraySize = optionArraySizeNew;
    }

    public int getOffsetOfOptionWithId(int wantedOptionId, int matchNumber) {
        int currentOptionOffset = 0;
        int currentDelta = 0;
        while(currentOptionOffset < optionArraySize) {
            int currentOptionId = idOfOptionWithOffset(options, currentOptionOffset, currentDelta);
            if(currentOptionId == wantedOptionId) {     //first of the options has been found. iterate them until the right match number is found
                for(int i = 0; i<matchNumber; i++) {
                    currentOptionOffset = findOffsetOfNextOption(options, currentOptionOffset);
                    if(currentOptionOffset == optionArraySize || (options[currentOptionOffset] & 0xF0) != 0x00) {
                        return -1;  //array length has been exceeded or the delta is not 0, i.e. an option with an higher id was found
                    }
                }
                return currentOptionOffset;
            }
            if(currentOptionId > wantedOptionId) {
                return -1;
            }
            currentDelta = currentOptionId;
            currentOptionOffset = findOffsetOfNextOption(options, currentOptionOffset);
        }
        return -1;
    }

    public byte[] getValueOfOptionWithOffset(int offset) {
        int valueSize = getValueSizeOfOptionWithOffset(options, offset);
        int headerSize = headerSizeOfOptionWithOffset(options, offset);
        offset += headerSize;
        byte[] value = new byte[valueSize];
        if(valueSize>0) {
            Util.copyData(options, offset, value, 0, valueSize);
        }
        return value;
    }

    public int getValueSizeOfOptionWithOffset(int offset) {
        return getValueSizeOfOptionWithOffset(options, offset);
    }
    
        public void removeOptionWithOffset(int offset) {
        //1. get delta of this option
        int delta = idOfOptionWithOffset(options, offset, 0);	//this method with 0 as previous delta gives just the delta of this option

        //2. get length of the block to remove
        int optionSize = headerSizeOfOptionWithOffset(options, offset)
                + getValueSizeOfOptionWithOffset(options, offset);

        //3. recalculate next option's new delta value
        int offsetRightOption = offset + optionSize;	//same as findOffsetOfNextOption(options, offset);
        int deltaRightOption;
        int deltaFieldSizeRightOption = 0;
        int deltaRightOptionNew = 0;
        int deltaFieldSizeRightOptionNew = 0;
        int deltaFieldSizeDifferenceRightOption = 0;
        if(offsetRightOption != optionArraySize) {
            //get the old field size for the next option
            deltaRightOption = idOfOptionWithOffset(options, offsetRightOption, 0);	//this method with 0 as previous delta gives just the delta of this option
            deltaFieldSizeRightOption = optionExtendedDeltaFieldSize(options, offsetRightOption);
            //recalculate delta field size for next option
            deltaRightOptionNew = delta + deltaRightOption;
            deltaFieldSizeRightOptionNew = getExtendedOptionFieldSizeFor(deltaRightOptionNew);
            //determine the size difference between the new and the old field
            deltaFieldSizeDifferenceRightOption = deltaFieldSizeRightOptionNew - deltaFieldSizeRightOption;
        }

        //6. calculate new array size
        int optionArraySizeNew = optionArraySize
                - optionSize
                + deltaFieldSizeDifferenceRightOption;

        //7. allocate mem for new option array
        byte[] optionsNew = new byte[optionArraySizeNew];

        //8. copy old option array to the start of the option to remove
        if (offset>0)
            Util.copyData(options, 0, optionsNew, 0, offset);
        int offsetNew = offset;
        offset += optionSize;

        //only if a next option exists
        if(offsetRightOption != optionArraySize) {
            //9. write new delta for next option
            //length stays constant, delta is erased
            optionsNew[offsetNew] = (byte) (options[offset] & 0x0F);

            //write recalculated delta to the next option
            if(deltaFieldSizeRightOptionNew == 1) {
                optionsNew[offsetNew] += 13 << 4;
                optionsNew[offsetNew+1] = (byte) (deltaRightOptionNew-13);
            }
            else if(deltaFieldSizeRightOptionNew == 2){
                optionsNew[offsetNew] += 14 << 4;
                Util.set16(optionsNew, offsetNew+1, deltaRightOptionNew-269);
            }
            else { //deltaFieldSizeRightOptionNew == 0
                optionsNew[offsetNew] += deltaRightOptionNew << 4;
            }

            //jump behind the next option's extended delta field delta in the new array
            offsetNew += 1+deltaFieldSizeRightOptionNew;
            //jump behind the next option's extended delta field in the old array
            offset += 1+deltaFieldSizeRightOption;

            //10. copy rest of the array
            int restLength = optionArraySizeNew - offsetNew;
            Util.copyData(options, offset, optionsNew, offsetNew, restLength);
        }
        options = optionsNew;
        optionArraySize = optionArraySizeNew;

    }

    public void removeOptionWithId(int id, int matchNumber) {
        int offset = getOffsetOfOptionWithId(id, matchNumber);
        removeOptionWithOffset(offset);
    }

    public byte[] valueOfOptionWithId(int id, int no) {
        int partNo = 0;
        int offset = getOffsetOfOptionWithId(id, no);
        if (offset>=0)   {
            int valueSize = getValueSizeOfOptionWithOffset(offset);
            byte[] data = getValueOfOptionWithOffset(offset);
            return data;
        }
        return null;
    }
    
    public int findOffsetOfNextOption(int offset) {
        return findOffsetOfNextOption(this.options, offset);
    }
    
    public int idOfOptionWithOffset(int offset, int currentDelta) {
    	return idOfOptionWithOffset(this.options, offset, currentDelta);
    }

    public void encodeTo(byte[] buffer, int offset) {
        int iOffset = 0;
        Util.copyData(header, 0, buffer, offset, 4);
        iOffset+=4;

        byte tokenLength = this.getTokenLength();
        if (tokenLength > 0) {
            Util.copyData(token,0, buffer, offset+iOffset, tokenLength);
            iOffset += tokenLength;
        }

        if (optionArraySize>0) {
            Util.copyData(options, 0, buffer, offset+iOffset, optionArraySize);
            iOffset += optionArraySize;
        }

        if (this.payloadLength!=0) {
            buffer[offset+iOffset] = (byte) 0xFF;
            iOffset++;
            Util.copyData(this.payload,0, buffer, offset+iOffset, this.payloadLength);
        }
    }
    
    //
    // Return:
    // 0: OK
    // -1: Protocol error
    // -2: Currently unsupported feature

    public byte decode(byte[] inBuffer, int offset, int len) {
        //##if LOGGING
        Logger.appendString(csr.s2b("CoAPDecode :: ENTER DECODE"));
        Logger.flush(Mote.INFO);
        //##endif

        int inOffset = offset;
        int endLen = offset+len;

        payloadLength = 0;

        Util.copyData(inBuffer, offset, header, 0, 4);

        inOffset += 4;

        // Read token
        byte tokenLength = getTokenLength();
		
		if(tokenLength > 8) {
			return -1;
		}
		
		if(inOffset == -1) {
			return -1;
		}

        //##if LOGGING
        Logger.appendString(csr.s2b("CoAPDecode :: token len"));
        Logger.appendInt(tokenLength);
        Logger.flush(Mote.INFO);
        //##endif

        if (tokenLength>0) {
            token = new byte[tokenLength];
            Util.copyData(inBuffer, inOffset, token, 0, tokenLength);
        }

        inOffset += tokenLength;

        // Check if end of Message
        if (inOffset >= endLen) // Zero length Message, zero options
            return 0;

        //##if LOGGING
        Logger.appendString(csr.s2b("CoAPDecode :: start reading options "));
        Logger.flush(Mote.INFO);
        //##endif

        // Check if payload marker or options
        int optionOffset = inOffset;
        inOffset = jumpOverOptions(inBuffer, inOffset, endLen);
		
		if(inOffset == -1) {
			return -1;
		}

        //##if LOGGING
        Logger.appendString(csr.s2b("CoAPDecode :: new offset"));
        Logger.appendInt(inOffset);
        Logger.appendString(csr.s2b("CoAPDecode :: endlen"));
        Logger.appendInt(endLen);

        Logger.flush(Mote.INFO);
        //##endif

        optionArraySize = inOffset - optionOffset; //may be 0 if no options are given
        options = new byte[optionArraySize];
        if(optionArraySize > 0) {
            Util.copyData(inBuffer, optionOffset, options, 0, optionArraySize);
        }

        //##if LOGGING
        Logger.appendString(csr.s2b("CoAPDecode :: end reading options "));
        Logger.flush(Mote.INFO);
        //##endif

        if (inOffset == endLen) { // Zero length Message
            //##if LOGGING
            Logger.appendString(csr.s2b("CoAPDecode :: no payload  "));
            Logger.flush(Mote.INFO);
            //##endif
            return 0;
        }
		
        if (inBuffer[inOffset] == (byte) 0xFF)    {
			inOffset++;
            if(inOffset == endLen) {	//protocol error: there is no payload though the marker indicates
				//##if LOGGING
				Logger.appendString(csr.s2b("CoAPDecode :: protocol error  "));
				Logger.flush(Mote.INFO);
				//##endif
				return -1;
			}

			// Payload
			payloadLength = endLen-inOffset;
			payload = new byte[payloadLength];
			Util.copyData(inBuffer, inOffset, payload, 0, payloadLength);
        }
		else {
			inOffset++;
			if(inOffset < endLen) { //protocol error: there is payload though there is no marker
				//##if LOGGING
				Logger.appendString(csr.s2b("CoAPDecode :: protocol error  "));
				Logger.flush(Mote.INFO);
				//##endif
				return -1;

			}
		}
        return 0;
    }

    public final int getMessageLength() {
        int len=4+getTokenLength();

        if (this.payloadLength!=0)
            len += 1+payloadLength;

        len += optionArraySize;
        return len;
    }

    public final Packet prepareResponseForSourcePacket(int inDstPort, byte[] inDstAddr, byte[] inSrcAddr, int srcPort) {
        int dstport = inDstPort;
        int lenp = getMessageLength();

        Packet tempPacket = Mac.getPacket();
        tempPacket.release();
        Address.copyAddress(inDstAddr, 0, tempPacket.dstaddr, 0);
        Address.copyAddress(inSrcAddr, 0, tempPacket.srcaddr, 0);
        tempPacket.create(dstport, srcPort, lenp);
        encodeTo(tempPacket.payloadBuf, tempPacket.payloadOff);
        return tempPacket;
    }

    private static int jumpOverOptions(byte[] inBuffer, int offset, int len) {
        int nextOptionOffset = offset;
        while(nextOptionOffset < len && inBuffer[nextOptionOffset] != (byte) 0xFF) {
            //  checking for protocol violation -- one of the nibbles is F but it's not the payload marker
            //    check belongs only here since the first time parsing of a received message happens here
            if( (inBuffer[offset] & 0x0F) == 0x0F || (inBuffer[offset] & 0xF0) == 0xF0 ) {
                return -1;
            }
            nextOptionOffset = findOffsetOfNextOption(inBuffer, nextOptionOffset);
        }
        return nextOptionOffset;
    }

    private static int findOffsetOfNextOption(byte[] inBuffer, int offset) {
        int headerSize = headerSizeOfOptionWithOffset(inBuffer, offset);
        int valueSize = getValueSizeOfOptionWithOffset(inBuffer, offset);
        int currentOptionSize = headerSize + valueSize;
        return offset + currentOptionSize;
    }

    private static int headerSizeOfOptionWithOffset(byte[] inBuffer, int offset) {
        int size = 1;
        size += optionExtendedDeltaFieldSize(inBuffer, offset);
        size += optionExtendedLengthFieldSize(inBuffer, offset);
        return size;
    }

    private static int optionExtendedDeltaFieldSize(byte[] inBuffer, int offset) {
        byte optionDelta = (byte)  (((inBuffer[offset] & 0xF0) >> 4));
        if(optionDelta < 13)
            return 0;
        if(optionDelta == 13)
            return 1;
        return 2;     //optionDelta == 14
    }

    private static int optionExtendedLengthFieldSize(byte[] inBuffer, int offset) {
        byte optionLength = (byte)  (inBuffer[offset] & 0x0F);
        if(optionLength < 13)
            return 0;
        if(optionLength == 13)
            return 1;
        return 2; //optionLength == 14
    }

    private static int getValueSizeOfOptionWithOffset(byte[] inBuffer, int offset) {
        byte optionLength = (byte)  (inBuffer[offset] & 0x0F);
        if(optionLength < 13)
            return optionLength;
        else {
            offset += 1 + optionExtendedDeltaFieldSize(inBuffer, offset);
            if(optionLength == 13) {
                return inBuffer[offset] + 13;
            }
            return Util.get16(inBuffer, offset) + 269; //optionLength == 14
        }
    }

    private static int idOfOptionWithOffset(byte[] inBuffer, int offset, int currentDelta) {
        byte optionDelta = (byte)  (((inBuffer[offset] & 0xF0) >> 4));
        if(optionDelta < 13)
            return currentDelta + optionDelta;
        else {
            offset += 1;
            if(optionDelta == 13) {
                return currentDelta + inBuffer[offset] + 13;
            }
            return currentDelta + Util.get16(inBuffer, offset) + 269;  //optionDelta == 14
        }
    }

    private static int getExtendedOptionFieldSizeFor(int input) {
        if(input<13)
            return 0;
        else if(input >= 13 && input < 269)
            return 1;
        return 2;    //input >= 269
    }
}