/*
 * Copyright (C) 2014 Dell, Inc.
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

package com.dell.doradus.service.db.s3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.http.client.CredentialsProvider;
import org.jets3t.service.Jets3tProperties;
import org.jets3t.service.S3Service;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.security.AWSCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dell.doradus.common.UserDefinition;
import com.dell.doradus.core.ServerParams;
import com.dell.doradus.service.db.ColumnDelete;
import com.dell.doradus.service.db.ColumnUpdate;
import com.dell.doradus.service.db.DBService;
import com.dell.doradus.service.db.DBTransaction;
import com.dell.doradus.service.db.DColumn;
import com.dell.doradus.service.db.RowDelete;
import com.dell.doradus.service.db.Tenant;

public class AmazonS3Service extends DBService {
    private static final byte[] EMPTY_BYTES = new byte[0];
    protected final Logger m_logger = LoggerFactory.getLogger(getClass().getSimpleName());
    private static final AmazonS3Service INSTANCE = new AmazonS3Service();
    private AmazonConnection m_connection;
    private static ExecutorService s3_executor;
    
    
    private AmazonS3Service() { }

    public static AmazonS3Service instance() { return INSTANCE; }
    
    @Override public void initService() {
        String accessKey = ServerParams.instance().getModuleParamString("AmazonS3Service", "s3-access-key");
        String secretKey = ServerParams.instance().getModuleParamString("AmazonS3Service", "s3-secret-key");
        String bucket = ServerParams.instance().getModuleParamString("AmazonS3Service", "s3-bucket");

        AWSCredentials awsCredentials = new AWSCredentials(accessKey, secretKey); 
        S3Service s3service = new RestS3Service(awsCredentials);
        Jets3tProperties props = s3service.getJetS3tProperties();
        
        String port = ServerParams.instance().getModuleParamString("AmazonS3Service", "s3-endpoint-http-port");
        String httpsOnly = ServerParams.instance().getModuleParamString("AmazonS3Service", "s3-https-only");
        String endpoint = ServerParams.instance().getModuleParamString("AmazonS3Service", "s3-endpoint");
        if(port != null) props.setProperty("s3service.s3-endpoint-http-port", port);
        if(httpsOnly != null) props.setProperty("s3service.https-only", httpsOnly);
        if(endpoint != null) props.setProperty("s3service.s3-endpoint", endpoint);
        //props.setProperty("s3service.s3-endpoint-http-port", "10453");
        //props.setProperty("s3service.https-only", "false");
        //props.setProperty("s3service.s3-endpoint", "localhost");
        props.setProperty("s3service.disable-dns-buckets", "true");
        CredentialsProvider credentials = s3service.getCredentialsProvider();
        s3service = new RestS3Service(awsCredentials, "Doradus", credentials, props);
        
        Object str_threads = ServerParams.instance().getModuleParam("AmazonS3Service", "s3-threads");
        int threads = str_threads == null ? 1 : Integer.parseInt(str_threads.toString());
        s3_executor = Executors.newFixedThreadPool(threads);
        
        m_connection = new AmazonConnection(s3service, bucket);
    }

    @Override public void startService() { }
    
    @Override public void stopService() {
        s3_executor.shutdownNow();
    }
    
    @Override public void createTenant(Tenant tenant, Map<String, String> options) {
        //?? how to create tenant?
    }

    @Override public void modifyTenant(Tenant tenant, Map<String, String> options) {
        throw new RuntimeException("Not implemented");
    }

    @Override public void dropTenant(Tenant tenant) {
        m_connection.deleteAll(tenant.getKeyspace());
    }
    
    
    @Override public void addUsers(Tenant tenant, Iterable<UserDefinition> users) {
        throw new RuntimeException("This method is not supported");
    }
    
    @Override public void modifyUsers(Tenant tenant, Iterable<UserDefinition> users) {
        throw new RuntimeException("This method is not supported");
    }
    
    @Override public void deleteUsers(Tenant tenant, Iterable<UserDefinition> users) {
        throw new RuntimeException("This method is not supported");
    }
    
    @Override public Collection<Tenant> getTenants() {
        List<Tenant> tenants = new ArrayList<>();
        List<ListItem> keyspaces = m_connection.listAll("/");
        for(ListItem keyspace: keyspaces) {
            tenants.add(new Tenant(keyspace.name));
        }
        return tenants;
    }

    @Override public void createStoreIfAbsent(Tenant tenant, String storeName, boolean bBinaryValues) {
        //???
    }
    
    @Override public void deleteStoreIfPresent(Tenant tenant, String storeName) {
        m_connection.deleteAll(tenant.getKeyspace() + "/" + storeName);
    }
    
    public String encode(String name) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if(c != '_' && Character.isLetterOrDigit(c)) sb.append(c);
            else {
                String esc = String.format("_%02x", (int)c);
                sb.append(esc);
            }
        }
        return sb.toString();
    }
    
    public String decode(String name) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if(c != '_') sb.append(c);
            else {
                c = (char)Integer.parseInt(name.substring(i + 1, i + 3), 16);
                sb.append(c);
                i += 2;
            }
        }
        return sb.toString();
    }
    
    @Override public void commit(DBTransaction dbTran) {
        List<Future<?>> futures = new ArrayList<>();
    	String keyspace = dbTran.getNamespace();
        //1. update
        for(ColumnUpdate mutation: dbTran.getColumnUpdates()) {
            String store = mutation.getStoreName();
            String row = mutation.getRowKey();
            DColumn c = mutation.getColumn();
            String column = c.getName();
            final byte[] value = c.getRawValue();
            final String path = keyspace + "/" + store + "/" + encode(row) + "/" + encode(column);
            futures.add(s3_executor.submit(new Runnable(){
                @Override public void run() {
                    m_connection.put(path, value);
                }
            }));
        }
        //2. delete columns
        for(ColumnDelete mutation: dbTran.getColumnDeletes()) {
            String store = mutation.getStoreName();
            String row = mutation.getRowKey();
            String column = mutation.getColumnName();
            final String path = keyspace + "/" + store + "/" + encode(row) + "/" + encode(column);
            futures.add(s3_executor.submit(new Runnable(){
                @Override public void run() {
                    m_connection.delete(path);
                }
            }));
        }
        //3. delete rows
        for(RowDelete mutation: dbTran.getRowDeletes()) {
            String store = mutation.getStoreName();
            String row = mutation.getRowKey();
            String path = keyspace + "/" + store + "/" + encode(row);
            futures.add(s3_executor.submit(new Runnable(){
                @Override public void run() {
                    m_connection.deleteAll(path);
                }
            }));
        }
        wait(futures);
    }
    
    @Override
    public List<DColumn> getColumns(String namespace, String storeName,
            String rowKey, String startColumn, String endColumn, int count) {
        List<Future<?>> futures = new ArrayList<>();
        final ArrayList<DColumn> list = new ArrayList<>();
        final String path = namespace + "/" + storeName + "/" + encode(rowKey) + "/";
        List<ListItem> keys = m_connection.listAll(path);
        for(ListItem item: keys) {
            final String key = item.name;
            String name = decode(key);
            if(startColumn != null && startColumn.compareTo(name) > 0) continue;
            if(endColumn != null && endColumn.compareTo(name) <= 0) continue;
            futures.add(s3_executor.submit(new Runnable(){
                @Override public void run() {
                    byte[] value = EMPTY_BYTES;
                    if(item.hasContents) {
                        value = m_connection.get(path + key);
                    }
                    if(value != null) {
                        synchronized (list) {
                            list.add(new DColumn(name, value));
                        }
                    }
                }
            }));
        }
        wait(futures);
        Collections.sort(list);
        return list;
    }

    @Override
    public List<DColumn> getColumns(String namespace, String storeName, String rowKey, Collection<String> columnNames) {
        List<Future<?>> futures = new ArrayList<>();
        final ArrayList<DColumn> list = new ArrayList<>();
        final String path = namespace + "/" + storeName + "/" + encode(rowKey) + "/";
        for(String columnName: columnNames) {
            futures.add(s3_executor.submit(new Runnable() {
                @Override public void run() {
                    byte[] value = m_connection.get(path + encode(columnName));
                    if(value != null) {
                        synchronized (list) {
                            list.add(new DColumn(columnName, value));
                        }
                    }
                }
            }));
        }
        wait(futures);
        Collections.sort(list);
        return list;
    }

    @Override
    public List<String> getRows(String namespace, String storeName, String continuationToken, int count) {
        List<String> rows = new ArrayList<>();
        String path = namespace + "/" + storeName + "/";
        List<ListItem> rowKeys = m_connection.listAll(path);
        for(ListItem item: rowKeys) {
            String row = item.name;
            String rowName = decode(row);
            if(continuationToken != null && continuationToken.compareTo(rowName) >= 0) continue;
            rows.add(rowName);
            if(rows.size() >= count) break;
        }
        return rows;
    }

    private void wait(List<Future<?>> futures) {
        try {
            for(Future<?> future: futures) {
                future.get();
            }
        }catch(ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
