/**
 * This is a very small (and incomplete) implementation of RFC 6690
 *
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
 */
 
package com.sap.coap;

import com.ibm.saguaro.system.*;
import com.ibm.iris.*;
import com.ibm.saguaro.mrv6.*;


public final class Core {
//    @Immutable
    public static byte[] coreString = new byte[128];
    
    public static int coreStringLength = 0;
  
    public static void registerResourceString(byte[] resource, int len) {
    //   Util.updatePersistentData(resource,0,coreString,0, len); 
       Util.copyData(resource,0,coreString,0,len);     
       coreStringLength = len;    
    }
    
    public static void registerResource(byte [] resource, int len) {
       if (coreStringLength>0) {
         Util.updatePersistentData(csr.s2b(","),0,coreString,coreStringLength, len);      
         coreStringLength += 1;      
       }
       
       Util.updatePersistentData(resource,0,coreString,coreStringLength, len);      
       coreStringLength += len;
    }

    public static Message handleCore() {
     Message coapMessage = new Message();
     /*  coapMessage.payload = coreString;
       coapMessage.payloadLength = coreStringLength;
       coapMessage.options = new byte[4];
       coapMessage.options[0] = 12;
       Util.set16(coapMessage.options, 1, 4);
       coapMessage.options[3] = 40;
       coapMessage.optionArrayLength = 4;
         
                        
       coapMessage.setType((byte) 2);
       coapMessage.header[1]=(byte) 69;
*/      return coapMessage;
    }

}