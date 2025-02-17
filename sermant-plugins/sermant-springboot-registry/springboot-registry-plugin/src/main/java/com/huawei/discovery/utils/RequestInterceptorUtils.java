/*
 * Copyright (C) 2022-2022 Huawei Technologies Co., Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.discovery.utils;

import com.huawei.discovery.entity.ServiceInstance;
import com.huawei.discovery.entity.SimpleRequestRecorder;
import com.huawei.discovery.retry.InvokerContext;

import com.huaweicloud.sermant.core.common.LoggerFactory;
import com.huaweicloud.sermant.core.plugin.agent.entity.ExecuteContext;
import com.huaweicloud.sermant.core.utils.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 解析url参数、构建公共方法相关工具类
 *
 * @author chengyouling
 * @since 2022-10-09
 */
public class RequestInterceptorUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger();

    private static final SimpleRequestRecorder RECORDER = new SimpleRequestRecorder();

    private RequestInterceptorUtils() {
    }

    /**
     * 解析url参数信息 http://www.domain.com/serviceName/sayHello?name=1
     *
     * @param url url地址
     * @return url解析后的主机名、路径
     */
    public static Map<String, String> recovertUrl(String url) {
        if (StringUtils.isEmpty(url)) {
            return Collections.emptyMap();
        }
        String temp = url.substring(url.indexOf(HttpConstants.HTTP_URL_DOUBLIE_SLASH)
            + HttpConstants.HTTP_URL_DOUBLIE_SLASH.length());
        int slashLen = 1;

        // 剔除域名之后的path
        temp = temp.substring(temp.indexOf(HttpConstants.HTTP_URL_SINGLE_SLASH) + slashLen);

        // 服务名
        int index = temp.indexOf(HttpConstants.HTTP_URL_SINGLE_SLASH);
        if (index == -1) {
            return Collections.emptyMap();
        }
        String host = temp.substring(0, index);

        // 请求路径
        String path = temp.substring(temp.indexOf(HttpConstants.HTTP_URL_SINGLE_SLASH));
        Map<String, String> result = new HashMap<>();
        result.put(HttpConstants.HTTP_URI_HOST, host);
        String scheme = url.substring(0, url.indexOf(HttpConstants.HTTP_URL_DOUBLIE_SLASH));
        result.put(HttpConstants.HTTP_URL_SCHEME, scheme);
        result.put(HttpConstants.HTTP_URI_PATH, path);
        return result;
    }

    /**
     * 针对HttpUrlConnection进行地址重构
     *
     * @param originUrl 原地址
     * @param instance 选择的实例
     * @param path 路径
     * @return URL
     */
    public static Optional<URL> rebuildUrlForHttpConnection(URL originUrl, ServiceInstance instance, String path) {
        final String protocol = originUrl.getProtocol();
        String newUrl = String.format(Locale.ENGLISH, "%s://%s:%s%s", protocol, instance.getIp(), instance.getPort(),
                path);
        try {
            LOGGER.fine(String.format(Locale.ENGLISH, "[HttpUrlConnection] rebuild url %s", newUrl));
            return Optional.of(new URL(newUrl));
        } catch (MalformedURLException e) {
            LOGGER.warning(String.format(Locale.ENGLISH, "Can not parse url %s to URL", newUrl));
        }
        return Optional.empty();
    }

    /**
     * 打印请求路径
     *
     * @param hostAndPath 请求路径与服务名
     * @param source 请求原， 例如httpclient/http async client
     */
    public static void printRequestLog(String source, Map<String, String> hostAndPath) {
        if (!RECORDER.isEnable()) {
            return;
        }
        String path = String.format(Locale.ENGLISH, "/%s%s", hostAndPath.get(HttpConstants.HTTP_URI_HOST),
            hostAndPath.get(HttpConstants.HTTP_URI_PATH));
        LOGGER.log(Level.FINE, String.format(Locale.ENGLISH, "[%s] request [%s] has been intercepted!", source, path));
        RECORDER.beforeRequest();
    }

    /**
     * 格式化uri
     *
     * @param uri 目标uri
     * @return URI
     */
    public static Optional<URI> formatUri(String uri) {
        if (!isValidUrl(uri)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URI(uri));
        } catch (URISyntaxException e) {
            LOGGER.fine(String.format(Locale.ENGLISH, "%s is not valid uri!", uri));
            return Optional.empty();
        }
    }

    private static boolean isValidUrl(String url) {
        final String lowerCaseUrl = url.toLowerCase(Locale.ROOT);
        return lowerCaseUrl.startsWith("http") || lowerCaseUrl.startsWith("https");
    }

    /**
     * 构建invoke回调方法函数
     *
     * @param context 上下文
     * @param invokerContext 调用上下文
     * @return 调用器
     */
    public static Supplier<Object> buildFunc(ExecuteContext context, InvokerContext invokerContext) {
        return buildFunc(context.getObject(), context.getMethod(), context.getArguments(), invokerContext);
    }

    /**
     * 构建invoke回调方法函数
     *
     * @param target 目标
     * @param arguments 参数
     * @param method 方法
     * @param invokerContext 调用上下文
     * @return 调用器
     */
    public static Supplier<Object> buildFunc(Object target, Method method, Object[] arguments,
            InvokerContext invokerContext) {
        return () -> {
            try {
                if (!method.isAccessible()) {
                    AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                        method.setAccessible(true);
                        return method;
                    });
                }
                return method.invoke(target, arguments);
            } catch (IllegalAccessException e) {
                LOGGER.log(Level.SEVERE, String.format(Locale.ENGLISH, "Can not invoke method [%s]",
                        method.getName()), e);
            } catch (InvocationTargetException e) {
                invokerContext.setEx(e.getTargetException());
                LOGGER.log(Level.FINE, String.format(Locale.ENGLISH, "invoke method [%s] failed",
                        method.getName()), e.getTargetException());
            }
            return Optional.empty();
        };
    }

    /**
     * 构建ip+端口url
     *
     * @param urlIfo
     * @param serviceInstance
     * @return url
     */
    public static String buildUrl(Map<String, String> urlIfo, ServiceInstance serviceInstance) {
        StringBuilder urlBuild = new StringBuilder();
        urlBuild.append(urlIfo.get(HttpConstants.HTTP_URL_SCHEME))
            .append(HttpConstants.HTTP_URL_DOUBLIE_SLASH)
            .append(serviceInstance.getIp())
            .append(HttpConstants.HTTP_URL_COLON)
            .append(serviceInstance.getPort())
            .append(urlIfo.get(HttpConstants.HTTP_URI_PATH));
        return urlBuild.toString();
    }

    /**
     * 解析host、path信息
     *
     * @param path
     * @return host、path信息集合
     */
    public static Map<String, String> recoverHostAndPath(String path) {
        Map<String, String> result = new HashMap<>();
        if (StringUtils.isEmpty(path)) {
            return result;
        }
        int startIndex = 0;
        while (startIndex < path.length() && path.charAt(startIndex) == HttpConstants.HTTP_URL_SINGLE_SLASH) {
            startIndex++;
        }
        String tempPath = path.substring(startIndex);
        if (tempPath.indexOf(HttpConstants.HTTP_URL_SINGLE_SLASH) <= 0) {
            return result;
        }
        result.put(HttpConstants.HTTP_URI_HOST,
            tempPath.substring(0, tempPath.indexOf(HttpConstants.HTTP_URL_SINGLE_SLASH)));
        result.put(HttpConstants.HTTP_URI_PATH,
            tempPath.substring(tempPath.indexOf(HttpConstants.HTTP_URL_SINGLE_SLASH)));
        return result;
    }

    /**
     * 构建包含ip、端口url
     *
     * @param uri
     * @param serviceInstance
     * @param path
     * @param method
     * @return ip:port构建的url
     */
    public static String buildUrlWithIp(URI uri, ServiceInstance serviceInstance, String path, String method) {
        StringBuilder urlBuild = new StringBuilder();
        urlBuild.append(uri.getScheme())
            .append(HttpConstants.HTTP_URL_DOUBLIE_SLASH)
            .append(serviceInstance.getIp())
            .append(HttpConstants.HTTP_URL_COLON)
            .append(serviceInstance.getPort())
            .append(path);
        if (method.equals(HttpConstants.HTTP_GET)) {
            urlBuild.append(HttpConstants.HTTP_URL_UNKNOWN)
                .append(uri.getQuery());
        }
        return urlBuild.toString();
    }
}
