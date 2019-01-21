package com.ibm.hybrid.cloud.sample.stocktrader.tradehistory.mongo;

import com.mongodb.MongoClient;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MapReduceIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.MongoCredential;
import com.mongodb.client.model.Filters;

import org.bson.Document;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Set;

import java.net.URL;
import java.net.MalformedURLException;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

import com.ibm.hybrid.cloud.sample.stocktrader.tradehistory.client.Quote;
import com.ibm.hybrid.cloud.sample.stocktrader.tradehistory.client.StockQuoteClient;
import com.ibm.hybrid.cloud.sample.stocktrader.tradehistory.demo.DemoConsumedMessage;

import org.eclipse.microprofile.rest.client.RestClientBuilder;

public class MongoConnector {
    private char[] MONGO_PASSWORD =  System.getenv("MONGO_PASSWORD").toCharArray();
    private String MONGO_DATABASE = System.getenv("MONGO_DATABASE");
    private String MONGO_USER = System.getenv("MONGO_USER");
    private String MONGO_IP = System.getenv("MONGO_IP");
    private int MONGO_PORT = Integer.parseInt(System.getenv("MONGO_PORT"));
    private String MONGO_COLLECTION = System.getenv("MONGO_COLLECTION");
    //TODO: add stock quote url to kube secrets
    private String STOCK_QUOTE_URL = System.getenv("STOCK_QUOTE_URL");

    private ServerAddress sa;
    private MongoCredential credential; 

    public static MongoDatabase database;
    public static MongoClient mongoClient;
    public MongoCollection<Document> tradesCollection;
    public static final String TRADE_DATABASE = "test_collection";

    
    @Inject 
    @RestClient  
    private StockQuoteClient stockQuoteClient;

    public MongoConnector(){
        //Mongo DB Connection
        sa = new ServerAddress(MONGO_IP,MONGO_PORT);
        credential = MongoCredential.createCredential(MONGO_USER, MONGO_DATABASE, MONGO_PASSWORD);
        mongoClient = new MongoClient(sa, Arrays.asList(credential));
        database = mongoClient.getDatabase( MONGO_COLLECTION );
        
        try {
            tradesCollection = database.getCollection(TRADE_DATABASE);
        } catch (IllegalArgumentException e) {
            database.createCollection(TRADE_DATABASE);
            tradesCollection = database.getCollection(TRADE_DATABASE);
        }
        try{
            URL stockQuoteUrl = new URL (STOCK_QUOTE_URL);
            stockQuoteClient = RestClientBuilder
                                    .newBuilder()
                                    .baseUrl(stockQuoteUrl)
                                    .build(StockQuoteClient.class);
        } catch (MalformedURLException e) {
            System.err.println("The given URL is not formatted correctly.");
        }
    }

    /*public void insertFile (DemoConsumedMessage dcm) {
        MongoCollection<Document> collection = database.getCollection("test_collection");
           Document doc = new Document("topic", dcm.getTopic())
                .append("partition", dcm.getPartition())
                .append("offset", dcm.getOffset())
                .append("value", dcm.getValue())
                .append("timestamp", dcm.getTimestamp());
            collection.insertOne(doc);
    }*/

    //{ "owner":"John", "symbol":"IBM", "shares":3, "price":120, "when":"now", "comission":0  } 
    public void insertStockPurchase(StockPurchase sp, DemoConsumedMessage dcm) {
        //Only add to DB if it's a valid Symbol 
        if( sp.getPrice() > 0 ) {
            MongoCollection<Document> collection = database.getCollection(TRADE_DATABASE);
            Document doc = new Document("topic", dcm.getTopic())
                    .append("id", sp.getId())
                    .append("owner", sp.getOwner())
                    .append("symbol", sp.getSymbol())
                    .append("shares", sp.getShares())
                    .append("price", sp.getPrice())
                    .append("notional", sp.getPrice() * sp.getShares())
                    .append("when", sp.getWhen())
                    .append("comission", sp.getCommission());
                collection.insertOne(doc);
        }
    }

    /*public void retrieveMostRecentDoc(){
        return database.getCollection().find().skip(database.getCollection().count() - 1);
    }*/

    public JSONObject getTrades(String ownerName) {
        // MongoCollection<Document> tradesCollection = database.getCollection(TRADE_DATABASE);

        FindIterable<Document> docs = this.tradesCollection.find(Filters.eq("owner", ownerName));
        return docsToJsonObject(docs, "transactions");
    }

    public JSONObject getTradesForSymbol(String ownerName, String symbol) {
        FindIterable<Document> docs = tradesCollection.find(Filters.and(Filters.eq("owner", ownerName), Filters.eq("symbol", symbol)));
        return docsToJsonObject(docs, "transactions");
    }

    private MongoIterable<Document> getSharesCount(String ownerName, String symbol) {
        MapReduceIterable<Document> docs = tradesCollection.mapReduce("function() { emit( this.symbol, this.shares); }", 
                                                                        "function(key, values) { return Array.sum(values) }")
                                            .filter(Filters.and(Filters.eq("owner", ownerName), Filters.eq("symbol", symbol)));
        return docs;
    }

    public JSONObject getSymbolShares(String ownerName, String symbol) {
        MongoIterable<Document> docs = getSharesCount(ownerName, symbol);
        JSONObject result = docsToJsonObject(docs, "shares");
        return result;
    }

    public JSONObject getPortfolioShares(String ownerName) {
        MapReduceIterable<Document> docs = tradesCollection.mapReduce("function() { emit( this.symbol, this.shares); }", 
                                                                        "function(key, values) { return Array.sum(values) }")
                                            .filter(Filters.eq("owner", ownerName));
        JSONObject result = docsToJsonObject(docs, "shares");
        return result;
    }

    public MongoIterable<Document> getTotalNotional(String ownerName) {
        MapReduceIterable<Document> docs = tradesCollection.mapReduce("function() { emit( this.symbol, this.notional); }", 
                                                                        "function(key, values) { return Array.sum(values) }")
                                            .filter(Filters.eq("owner", ownerName));
        // JSONObject result = docsToJsonObject(docs, "equity");
        // return result;
        return docs;
    }

    public JSONObject getSymbolNotional(String ownerName, String symbol) {
        MapReduceIterable<Document> docs = tradesCollection.mapReduce("function() { emit( this.symbol, this.notional); }", 
                                                                        "function(key, values) { return Array.sum(values) }")
                                            .filter(Filters.and(Filters.eq("owner", ownerName), Filters.eq("symbol", symbol)));
        JSONObject result = docsToJsonObject(docs, "equity");
        return result;
    }

    /**
     * 
     * @param ownerName
     * @return JSONObject containing array of equities
     */
    public JSONObject getPortfolioEquity(String ownerName, HttpServletRequest request) {
        // getPortfolioShares, iterate through and use StockQuote to get current price
            // to calculate equity per Symbol 
        String jwt = request.getHeader("Authorization");

        JSONArray jsonArray = new JSONArray();

        MongoIterable<Document> portfolioNotional = getTotalNotional(ownerName);
        for (Document item : portfolioNotional) {
            Double shares = (Double) item.get("value");

            String symbol = item.get("_id").toString();
            Double equity = getSymbolEquity(jwt, shares, symbol);

            JSONObject obj = new JSONObject();
            obj.put(item.get("_id").toString(), equity);
            jsonArray.put(obj);

        }
        JSONObject result = new JSONObject().put("equity", jsonArray);
        return result;
    }

    private Double getSymbolPrice(String jwt, String symbol) {
        System.out.println("StockQuoteClient: " + stockQuoteClient);
        System.out.println("JWT: " + jwt);
        Quote quote = new Quote();
        try {
            quote = stockQuoteClient.getStockQuote(jwt, symbol);
        } catch (Exception e) {
            System.out.println("Error in " + this.getClass().getName());
            System.out.println(e);
        }
        Double price = quote.getPrice();
        return price;
    }

	private Double getSymbolEquity(String jwt, Double shares, String symbol) {
        Double price = getSymbolPrice(jwt, symbol);
        Double equity = price * shares;
        return equity;
    }

    
    public Double getSymbolEquity(String jwt, String owner, String symbol) {

        Document doc = getSharesCount(owner, symbol).first(); //getSymbolShares(owner, symbol).get("shares");
        Double shares = doc.getDouble("value");
        return getSymbolEquity(jwt, shares, symbol);
    }

    /**
     * 
     * @param ownerName
     * @return total value of equity (no symbol breakdown)
     */
    public JSONObject getTotalEquity(String ownerName) {
        //TODO: getPortfolioEquity and reduce value
        

        JSONObject result = new JSONObject();
        return result;
    }

    public JSONObject getROI(String ownerName) {
        //TODO: equity minus notional
        JSONObject result = new JSONObject();
        return result;
    }

    private JSONObject docsToJsonObject(MongoIterable<Document> docs, String label) {
        JSONArray jsonArray = new JSONArray();
        JSONObject json = new JSONObject();
        for (Document doc : docs) {
            JSONObject obj = new JSONObject();

            Set<String> keys = doc.keySet();
            for (String key : keys) {
                obj.put(key, doc.get(key).toString());
            }

            jsonArray.put(obj);
        }

        json.put(label, jsonArray);
        return json;
    }    
}