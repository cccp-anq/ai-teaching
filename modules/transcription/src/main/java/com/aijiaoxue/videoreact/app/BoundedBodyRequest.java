package com.aijiaoxue.videoreact.app;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class BoundedBodyRequest extends HttpServletRequestWrapper {
 private final byte[] data;
 public BoundedBodyRequest(HttpServletRequest request,byte[] data){super(request);this.data=data;}
 @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(data);return new ServletInputStream(){public int read(){return input.read();}public int read(byte[] b,int off,int len){return input.read(b,off,len);}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Blocking JSON request");}};}
 @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
 @Override public int getContentLength(){return data.length;}
 @Override public long getContentLengthLong(){return data.length;}
}
