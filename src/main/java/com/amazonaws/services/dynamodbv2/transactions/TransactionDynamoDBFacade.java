/**
 * Copyright 2013-2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License"). You may not use
 * this file except in compliance with the License. A copy of the License is
 * located at
 *
 * http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express or
 * implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.services.dynamodbv2.transactions;

import java.math.BigDecimal;
import java.util.Map;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.ResponseMetadata;
import com.amazonaws.regions.Region;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemResult;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemResult;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.CreateTableResult;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteItemResult;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteTableResult;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.ExpectedAttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.ListTablesRequest;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.dynamodbv2.model.UpdateItemResult;
import com.amazonaws.services.dynamodbv2.model.UpdateTableRequest;
import com.amazonaws.services.dynamodbv2.model.UpdateTableResult;

/**
 * Facade for {@link AmazonDynamoDB} that forwards requests to a
 * {@link Transaction}, omitting conditional checks and consistent read options
 * from each request. Only supports the operations needed by DynamoDBMapper for
 * loading, saving or deleting items.
 */
public class TransactionDynamoDBFacade implements AmazonDynamoDB {

    private final Transaction txn;
    private final TransactionManager txManager;

    public TransactionDynamoDBFacade(Transaction txn, TransactionManager txManager) {
        this.txn = txn;
        this.txManager = txManager;
    }

    @Override
    public DeleteItemResult deleteItem(DeleteItemRequest request)
            throws AmazonServiceException, AmazonClientException {
        Map<String, ExpectedAttributeValue> expectedValues = request.getExpected();
        checkExpectedValues(request.getTableName(), request.getKey(), expectedValues);

        // conditional checks are handled by the above call
        request.setExpected(null);
        return txn.deleteItem(request);
    }

    @Override
    public GetItemResult getItem(GetItemRequest request)
            throws AmazonServiceException, AmazonClientException {
        return txn.getItem(request);
    }

    @Override
    public PutItemResult putItem(PutItemRequest request)
            throws AmazonServiceException, AmazonClientException {
        Map<String, ExpectedAttributeValue> expectedValues = request.getExpected();
        checkExpectedValues(request.getTableName(), Request.getKeyFromItem(request.getTableName(),
                request.getItem(), txManager), expectedValues);

        // conditional checks are handled by the above call
        request.setExpected(null);
        return txn.putItem(request);
    }

    @Override
    public UpdateItemResult updateItem(UpdateItemRequest request)
            throws AmazonServiceException, AmazonClientException {
        Map<String, ExpectedAttributeValue> expectedValues = request.getExpected();
        checkExpectedValues(request.getTableName(), request.getKey(), expectedValues);

        // conditional checks are handled by the above call
        request.setExpected(null);
        return txn.updateItem(request);
    }

    private void checkExpectedValues(String tableName,
            Map<String, AttributeValue> itemKey,
            Map<String, ExpectedAttributeValue> expectedValues) {
        if (expectedValues != null && !expectedValues.isEmpty()) {
            for (Map.Entry<String, ExpectedAttributeValue> entry : expectedValues.entrySet()) {
                if ((entry.getValue().isExists() == null || entry.getValue().isExists() == true)
                        && entry.getValue().getValue() == null) {
                    throw new IllegalArgumentException("An explicit value is required when Exists is null or true, "
                            + "but none was found in expected values for item with key " + itemKey +
                            ": " + expectedValues);
                }
            }

            // simulate by loading the item and checking the values;
            // this also has the effect of locking the item, which gives the
            // same behavior
            GetItemResult result = getItem(new GetItemRequest()
                    .withAttributesToGet(expectedValues.keySet())
                    .withKey(itemKey)
                    .withTableName(tableName));
            Map<String, AttributeValue> item = result.getItem();
            try {
                checkExpectedValues(expectedValues, item);
            } catch (ConditionalCheckFailedException e) {
                throw new ConditionalCheckFailedException("Item " + itemKey + " had unexpected attributes: " + e.getMessage());
            }
        }
    }

    /**
     * Checks a map of expected values against a map of actual values in a way
     * that's compatible with how the DynamoDB service interprets the Expected
     * parameter of PutItem, UpdateItem and DeleteItem.
     *
     * @param expectedValues
     *            A description of the expected values.
     * @param item
     *            The actual values.
     * @throws ConditionalCheckFailedException
     *             Thrown if the values do not match the expected values.
     */
    public static void checkExpectedValues(Map<String, ExpectedAttributeValue> expectedValues, Map<String, AttributeValue> item) {
        for (Map.Entry<String, ExpectedAttributeValue> entry : expectedValues.entrySet()) {
            // if the attribute is expected to exist (null for isExists means
            // true)
            if ((entry.getValue().isExists() == null || entry.getValue().isExists() == true)
                    // but the item doesn't
                    && (item == null
                            // or the attribute doesn't
                            || item.get(entry.getKey()) == null
                            // or it doesn't have the expected value
                            || !expectedValueMatches(entry.getValue().getValue(), item.get(entry.getKey())))) {
                throw new ConditionalCheckFailedException(
                        "expected attribute(s) " + expectedValues
                                + " but found " + item);
            } else if (entry.getValue().isExists() != null
                    && !entry.getValue().isExists()
                    && item != null && item.get(entry.getKey()) != null) {
                // the attribute isn't expected to exist, but the item exists
                // and the attribute does too
                throw new ConditionalCheckFailedException(
                        "expected attribute(s) " + expectedValues
                                + " but found " + item);
            }
        }
    }

    private static boolean expectedValueMatches(AttributeValue expected, AttributeValue actual) {
        if (expected.getN() != null) {
            return actual.getN() != null && new BigDecimal(expected.getN()).compareTo(new BigDecimal(actual.getN())) == 0;
        } else if (expected.getS() != null || expected.getB() != null) {
            return expected.equals(actual);
        } else {
            throw new IllegalArgumentException("Expect condition using unsupported value type: " + expected);
        }
    }

    @Override
    public BatchGetItemResult batchGetItem(BatchGetItemRequest arg0)
            throws AmazonServiceException, AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public BatchWriteItemResult batchWriteItem(BatchWriteItemRequest arg0)
            throws AmazonServiceException, AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public CreateTableResult createTable(CreateTableRequest arg0)
            throws AmazonServiceException, AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public DeleteTableResult deleteTable(DeleteTableRequest arg0)
            throws AmazonServiceException, AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public DescribeTableResult describeTable(DescribeTableRequest arg0)
            throws AmazonServiceException, AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public ResponseMetadata getCachedResponseMetadata(
            AmazonWebServiceRequest arg0) {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public ListTablesResult listTables() throws AmazonServiceException,
            AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public ListTablesResult listTables(ListTablesRequest arg0)
            throws AmazonServiceException, AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public QueryResult query(QueryRequest arg0) throws AmazonServiceException,
            AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public ScanResult scan(ScanRequest arg0) throws AmazonServiceException,
            AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public void setEndpoint(String arg0) throws IllegalArgumentException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public void setRegion(Region arg0) throws IllegalArgumentException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

    @Override
    public UpdateTableResult updateTable(UpdateTableRequest arg0)
            throws AmazonServiceException, AmazonClientException {
        throw new UnsupportedOperationException("Use the underlying client instance instead");
    }

}