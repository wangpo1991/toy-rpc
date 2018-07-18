package com.sinjinsong.toy.protocol.api.support;

import com.sinjinsong.toy.common.exception.RPCException;
import com.sinjinsong.toy.common.util.InvokeParamUtil;
import com.sinjinsong.toy.config.ReferenceConfig;
import com.sinjinsong.toy.filter.Filter;
import com.sinjinsong.toy.invoke.api.support.AbstractInvocation;
import com.sinjinsong.toy.invoke.async.AsyncInvocation;
import com.sinjinsong.toy.invoke.callback.CallbackInvocation;
import com.sinjinsong.toy.invoke.oneway.OneWayInvocation;
import com.sinjinsong.toy.invoke.sync.SyncInvocation;
import com.sinjinsong.toy.protocol.api.InvokeParam;
import com.sinjinsong.toy.protocol.api.Invoker;
import com.sinjinsong.toy.transport.api.Endpoint;
import com.sinjinsong.toy.transport.api.domain.RPCRequest;
import com.sinjinsong.toy.transport.api.domain.RPCResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * @author sinjinsong
 * @date 2018/7/14
 */
@Slf4j
public abstract class AbstractInvoker<T> implements Invoker<T> {
    protected Class<T> interfaceClass;
    protected Endpoint endpoint;
    
    @Override
    public RPCResponse invoke(InvokeParam invokeParam) throws RPCException {
        Function<RPCRequest, Future<RPCResponse>> logic = process();
        // 如果提交任务失败，则删掉该Endpoint，再次提交的话必须重新创建Endpoint
        AbstractInvocation invocation;
        ReferenceConfig referenceConfig = InvokeParamUtil.extractReferenceConfigFromInvokeParam(invokeParam);
        RPCRequest rpcRequest = InvokeParamUtil.extractRequestFromInvokeParam(invokeParam);
        if (referenceConfig.isAsync()) {
            invocation = new AsyncInvocation() {
                @Override
                protected Future<RPCResponse> doInvoke() {
                    return logic.apply(rpcRequest);
                }
            };
        } else if (referenceConfig.isCallback()) {
            invocation = new CallbackInvocation() {
                @Override
                protected Future<RPCResponse> doInvoke() {
                    return logic.apply(rpcRequest);
                }
            };
        } else if (referenceConfig.isOneWay()) {
            invocation = new OneWayInvocation() {
                @Override
                protected Future<RPCResponse> doInvoke() {
                    return logic.apply(rpcRequest);
                }
            };
        } else {
            invocation = new SyncInvocation() {
                @Override
                protected Future<RPCResponse> doInvoke() {
                    return logic.apply(rpcRequest);
                }
            };
        }
        invocation.setReferenceConfig(referenceConfig);
        invocation.setRpcRequest(rpcRequest);
        return invocation.invoke();
    }
    
    protected Function<RPCRequest,Future<RPCResponse>> process() {
        return null;
    }

    public <T> Invoker<T> buildFilterChain(List<Filter> filters) {
        AbstractInvoker<T> actualInvoker = (AbstractInvoker<T>) this;
        return new AbstractInvoker<T>() {
            @Override
            public Class<T> getInterface() {
                return actualInvoker.getInterface();
            }

            @Override
            public void setEndpoint(Endpoint endpoint) {
                actualInvoker.setEndpoint(endpoint);
            }

            @Override
            public String getAddress() {
                return actualInvoker.getAddress();
            }

            private ThreadLocal<AtomicInteger> filterIndex = new ThreadLocal() {
                @Override
                protected Object initialValue() {
                    return new AtomicInteger(0);
                }
            };

            @Override
            public RPCResponse invoke(InvokeParam invokeParam) throws RPCException {
                log.info("filterIndex:{}", filterIndex.get().get());
                final Invoker<T> invokerWrappedFilters = this;
                if (filterIndex.get().get() < filters.size()) {
                    return filters.get(filterIndex.get().getAndIncrement()).invoke(new AbstractInvoker() {
                        @Override
                        public Class<T> getInterface() {
                            return actualInvoker.getInterface();
                        }

                        @Override
                        public String getAddress() {
                            return actualInvoker.getAddress();
                        }

                        @Override
                        public RPCResponse invoke(InvokeParam invokeParam) throws RPCException {
                            return invokerWrappedFilters.invoke(invokeParam);
                        }
                    }, invokeParam);
                }
                filterIndex.get().set(0);
                return actualInvoker.invoke(invokeParam);
            }
        };
    }

    @Override
    public Class<T> getInterface() {
        return interfaceClass;
    }

    @Override
    public String getAddress() {
        return endpoint.getAddress();
    }

    public void setInterfaceClass(Class<T> interfaceClass) {
        this.interfaceClass = interfaceClass;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void close() {
        endpoint.close();
    }
}
