/**
 * Copyright (c) 2011 Toby Brain
 * 
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package com.createsend.util;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Map;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import com.createsend.models.ApiErrorResponse;
import com.createsend.models.PagedResult;
import com.createsend.util.exceptions.BadRequestException;
import com.createsend.util.exceptions.CreateSendException;
import com.createsend.util.exceptions.CreateSendHttpException;
import com.createsend.util.exceptions.NotFoundException;
import com.createsend.util.exceptions.ServerErrorException;
import com.createsend.util.exceptions.UnauthorisedException;
import com.createsend.util.jersey.JsonProvider;
import com.createsend.util.jersey.UserAgentFilter;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.api.client.filter.LoggingFilter;
import com.sun.jersey.core.util.MultivaluedMapImpl;

/**
 * An abstract base class for API calls. This class provides a basic facade to the Jersey API,
 * simplifying the creation of WebResources and standard access to the Campaign Monitor API
 */
public abstract class BaseClient {
    
    /**
     * As per Jersey docs the creation of a Client is expensive. We cache the client
     */
    private static Client client;
        
    /**
     * Performs a HTTP GET on the route specified by the pathElements deserialising the 
     * result to an instance of klass.
     * @param <T> The type of model expected from the API call.
     * @param klass The class of the model to deserialise. 
     * @param pathElements The path of the API resource to access
     * @return The model returned from the API call
     * @throws CreateSendException If the API call results in a HTTP status code >= 400
     */
    protected <T> T get(Class<T> klass, String... pathElements) throws CreateSendException {
        return get(klass, null, pathElements);
    }

    
    /**
     * Performs a HTTP GET on the route specified by the pathElements deserialising the 
     * result to an instance of klass.
     * @param <T> The type of model expected from the API call.
     * @param klass The class of the model to deserialise. 
     * @param queryString The query string params to use for the request. 
     * Use <code>null</code> when no query string is required.
     * @param pathElements The path of the API resource to access
     * @return The model returned from the API call
     * @throws CreateSendException If the API call results in a HTTP status code >= 400
     */
    protected <T> T get(Class<T> klass, MultivaluedMap<String, String> queryString,
        String... pathElements) throws CreateSendException {
        WebResource resource = getAPIResourceWithAuth(pathElements);
        
        if(queryString != null) {
            resource = resource.queryParams(queryString);
        }
        
        try {
            return fixStringResult(klass, resource.get(klass));
        } catch (UniformInterfaceException ue) {
            throw handleErrorResponse(ue);
        }   
    }
    
    /**
     * Performs a HTTP GET on the route specified attempting to deserialise the
     * result to a paged result of the given type.
     * @param <T> The type of paged result data expected from the API call. 
     * @param queryString The query string values to use for the request.
     * @param pathElements The path of the API resource to access
     * @return The model returned from the API call
     * @throws CreateSendException If the API call results in a HTTP status code >= 400
     */
    protected <T> PagedResult<T> getPagedResult(Integer page, Integer pageSize, String orderField, 
        String orderDirection, MultivaluedMap<String, String> queryString, String... pathElements) 
        throws CreateSendException {
        WebResource resource = getAPIResourceWithAuth(pathElements);
        if(queryString == null) queryString = new MultivaluedMapImpl();
        
        addPagingParams(queryString, page, pageSize, orderField, orderDirection);
        
        try {            
            String callingMethodName = Thread.currentThread().getStackTrace()[2].getMethodName();
            ParameterizedType genericReturnType = null;
            
            for(Method method : this.getClass().getMethods()) {
                if(method.getName().equals(callingMethodName)) {
                    genericReturnType = (ParameterizedType)method.getGenericReturnType();
                    break;
                }
            }            
            
            if(queryString != null) {
                resource = resource.queryParams(queryString);
            }
            
            return resource.get(new GenericType<PagedResult<T>>(genericReturnType));
        } catch (UniformInterfaceException ue) {
            throw handleErrorResponse(ue);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        
        return null;
    }
        
    /**
     * Posts the provided entity to the url specified by the provided path elements. 
     * The result of the call will be deserialised to an instance of the specified class.
     * @param <T> The class to use for model deserialisation
     * @param klass The class to use for model deserialisation
     * @param entity The entity to use as the body of the post request
     * @param pathElements The path to send the post request to
     * @return An instance of klass returned by the api call
     * @throws CreateSendException Thrown when the API responds with a HTTP Status >= 400
     */
    protected <T> T post(Class<T> klass, Object entity, String... pathElements) throws CreateSendException {
        WebResource resource = getAPIResourceWithAuth(pathElements);
        
        try { 
            return fixStringResult(klass, resource.
                type(MediaType.APPLICATION_JSON_TYPE).
                post(klass, entity));
        } catch (UniformInterfaceException ue) {
            throw handleErrorResponse(ue);
        }
    }
        
    /**
     * Makes a HTTP PUT request to the path specified, using the provided entity as the 
     * request body.
     * @param entity The entity to use as the request body
     * @param pathElements The path to make the request to.
     * @throws CreateSendException Raised when the API responds with a HTTP Status >= 400
     */
    protected void put(Object entity, String... pathElements) throws CreateSendException {
        WebResource resource = getAPIResourceWithAuth(pathElements);
        
        try { 
            resource.
                type(MediaType.APPLICATION_JSON_TYPE).
                put(entity);
        } catch (UniformInterfaceException ue) {
            throw handleErrorResponse(ue);
        }
    }
    
    /**
     * Makes a HTTP DELETE request to the specified path
     * @param pathElements The path of the resource to delete
     * @throws CreateSendException Raised when the API responds with a HTTP Status >= 400
     */
    protected void delete(String... pathElements) throws CreateSendException {
        WebResource resource = getAPIResourceWithAuth(pathElements);
        try { 
            resource.delete();
        } catch (UniformInterfaceException ue) {
            throw handleErrorResponse(ue);
        }
    }
        
    protected void addPagingParams(MultivaluedMap<String, String> queryString,  
        Integer page, Integer pageSize, String orderField, String orderDirection) {        
        if(page != null) {
            queryString.add("page", page.toString());
        }
        
        if(pageSize != null) {
            queryString.add("pagesize", pageSize.toString());
        }
        
        if(orderField != null) {
            queryString.add("orderfield", orderField); 
        }
        
        if(orderDirection != null) {
            queryString.add("orderdirection", orderDirection);
        }
    }
       
    /**
     * Creates an exception, inheriting from @{link com.createsend.util.exceptions.CreateSendException} 
     * to represent the API error resulting in the raised {@link com.sun.jersey.api.client.UniformInterfaceException}
     * @param ue The error raised during the failed API request
     * @return An exception representing the API error
     */
    protected CreateSendException handleErrorResponse(UniformInterfaceException ue) {
        ClientResponse response = ue.getResponse();
        ApiErrorResponse apiResponse;
        
        try { 
            apiResponse = response.getEntity(ApiErrorResponse.class);
        } catch (Throwable t) { 
            apiResponse = null;
        }
        
        switch(response.getClientResponseStatus()) {
            case BAD_REQUEST:
                return new BadRequestException(apiResponse.Code, apiResponse.Message);
            case INTERNAL_SERVER_ERROR:
                return new ServerErrorException(apiResponse.Code, apiResponse.Message);
            case NOT_FOUND:
                return new NotFoundException(apiResponse.Code, apiResponse.Message);
            case UNAUTHORIZED:
                return new UnauthorisedException(apiResponse.Code, apiResponse.Message);
            default:
                return new CreateSendHttpException(response.getClientResponseStatus());
        }
    }
    
    /**
     * @return A WebResource configured for use against the Campaign Monitor API.
     */
    protected WebResource getAPIResource() {
        if(client == null) {
            ClientConfig cc = new DefaultClientConfig(); 
            cc.getClasses().add(JsonProvider.class); 
            
            Map<String, Object> properties = cc.getProperties();
            properties.put(ClientConfig.PROPERTY_CHUNKED_ENCODING_SIZE, 64 * 1024);
            properties.put(com.sun.jersey.api.json.JSONConfiguration.FEATURE_POJO_MAPPING, "true");
            
            client = Client.create(cc);
            client.setFollowRedirects(false);
            
            if(Configuration.Current.isLoggingEnabled()) {
                client.addFilter(new LoggingFilter(System.out));
            }

            client.addFilter(new UserAgentFilter());
        }

        WebResource resource = client.resource(Configuration.Current.getApiEndpoint());
        resource.setProperty(ClientConfig.PROPERTY_CHUNKED_ENCODING_SIZE, 64 * 1024);
        resource.setProperty(com.sun.jersey.api.json.JSONConfiguration.FEATURE_POJO_MAPPING, "true");
        return resource;
    }
    
    /**
     * Creates a WebResource instance configured for the Campaign Monitor API, including an 
     * Authorization header with the API Key values specified in the current 
     * {@link com.createsend.util.Configuration}
     * @param pathElements The path to use when configuring the WebResource
     * @return A configured WebResource
     */
    protected WebResource getAPIResourceWithAuth(String... pathElements) {
        WebResource resource = getAPIResource();
        resource.addFilter(new HTTPBasicAuthFilter(Configuration.Current.getApiKey(), "x"));
                
        for(String pathElement : pathElements) {
            resource = resource.path(pathElement);
        }        
        
        return resource;
    }
    
    /**
     * Jersey is awesome in that even though we specify a JSON response and to use 
     * the {@link com.createsend.util.jersey.JsonProvider} it sees that we want a 
     * String result and that the response is already a String so just use that. 
     * This method strips any enclosing quotes required as per the JSON spec. 
     * @param <T> The type of result we are expecting
     * @param klass The class of the provided result
     * @param result The result as deserialised by Jersey
     * @return If the result if anything but a String just return the result. 
     * If the result is a String then strip any enclosing quotes ("). 
     */
    @SuppressWarnings("unchecked")
    protected <T> T fixStringResult(Class<T> klass, T result) {
        if(klass == String.class) { 
            String strResult = (String)result;
            if(strResult.startsWith("\"")) { 
                strResult = strResult.substring(1);
            }
            
            if(strResult.endsWith("\"")) {
                strResult = strResult.substring(0, strResult.length() - 1);
            }
            
            return (T)strResult;
        }
        
        return result;
    }
}
