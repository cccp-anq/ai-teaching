package com.aijiaoxue.supervision.web;

import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import com.aijiaoxue.supervision.evaluation.SupervisionImportRejectedException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Caps JSON bytes before Jackson materializes the evaluation import body. */
@ControllerAdvice
public final class SupervisionImportBodyLimit extends RequestBodyAdviceAdapter {
    // The parsed UTF-8 text limit is 5 MiB; leave room for JSON field names and escaping.
    static final long MAX_REQUEST_BYTES=6L*1024*1024;
    private final CurrentUserProvider currentUsers;
    private final PermissionService permissions;

    public SupervisionImportBodyLimit(CurrentUserProvider currentUsers,PermissionService permissions) {
        this.currentUsers=currentUsers; this.permissions=permissions;
    }

    @Override
    public boolean supports(MethodParameter method,Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType==SupervisionTextImportRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter method,Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        permissions.requireTimetableImport(currentUsers.requireCurrentUser());
        if(input.getHeaders().getContentLength()>MAX_REQUEST_BYTES)
            throw SupervisionImportRejectedException.fileTooLarge();
        var body=new BoundedInputStream(input.getBody(),MAX_REQUEST_BYTES);
        return new HttpInputMessage() {
            @Override public org.springframework.http.HttpHeaders getHeaders() { return input.getHeaders(); }
            @Override public InputStream getBody() { return body; }
        };
    }

    public static final class BodyTooLarge extends IOException {
        BodyTooLarge() { super("Evaluation import request exceeds transport size limit"); }
    }

    static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream source,long limit) { super(source); remaining=limit; }
        @Override public int read() throws IOException {
            int value=super.read();
            if(value<0) return value;
            if(remaining--<=0) throw new BodyTooLarge();
            return value;
        }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            if(length==0) return 0;
            int count=super.read(bytes,offset,(int)Math.min(length,remaining+1));
            if(count<0) return count;
            remaining-=count;
            if(remaining<0) throw new BodyTooLarge();
            return count;
        }
        @Override public long skip(long amount) throws IOException {
            long skipped=0;
            byte[] discard=new byte[8192];
            while(skipped<amount) {
                int count=read(discard,0,(int)Math.min(discard.length,amount-skipped));
                if(count<0) break;
                skipped+=count;
            }
            return skipped;
        }
        @Override public boolean markSupported() { return false; }
        @Override public void mark(int readlimit) {}
        @Override public void reset() throws IOException { throw new IOException("mark/reset is not supported"); }
    }
}
