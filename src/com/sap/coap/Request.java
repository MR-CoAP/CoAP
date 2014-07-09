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
 */

package com.sap.coap;

import com.ibm.saguaro.system.*;
import com.ibm.iris.*;
import com.ibm.saguaro.mrv6.*;

//##if LOGGING
import com.ibm.saguaro.logger.*;
//##endif

import com.sap.coap.Message;
import com.sap.coap.Core;

public class Request {
    public byte[] dstaddr;
    public int dstport;
    public byte[] srcaddr;
    public int srcport;

    public Message coapMessage;

    public Request(byte[] inDstAddr, int inDstPort, byte[] inSrcAddr, int inSrcPort, Message coap, boolean emptyPayload, boolean emptyOptions) {
        dstaddr = inDstAddr;
        srcaddr = inSrcAddr;
        dstport = inDstPort;
        srcport = inSrcPort;
        coapMessage = coap;

        if (emptyPayload == true)
            coapMessage.clearPayload();

        if (emptyOptions == true)
            coapMessage.clearOptions();
    }

    public Packet prepareResponsePacketForRequest(int lenp, int LOCAL_PORT) {
        int dstport = srcport;
        Packet tempPacket = Mac.getPacket();
        tempPacket.release();
        Address.copyAddress(srcaddr, 0, tempPacket.dstaddr, 0);
        Address.copyAddress(dstaddr, 0, tempPacket.srcaddr, 0);
        tempPacket.create(dstport, LOCAL_PORT, lenp);
        return tempPacket;
    }
}