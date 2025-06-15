(ns storage
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log])
  (:import
   (java.io
    InputStream)
   (java.net
    URI)
   (software.amazon.awssdk.auth.credentials
    AwsBasicCredentials
    StaticCredentialsProvider)
   (software.amazon.awssdk.core.sync
    RequestBody
    ResponseTransformer)
   (software.amazon.awssdk.regions
    Region)
   (software.amazon.awssdk.services.s3
    S3Client
    S3ClientBuilder
    S3Configuration)
   (software.amazon.awssdk.services.s3.model
    Bucket
    CreateBucketRequest
    DeleteObjectRequest
    GetObjectRequest
    HeadObjectRequest
    PutObjectRequest)))

(defn- bucket-exists?
  [^S3Client client bucket]
  (some #(= (.name ^Bucket %) bucket)
        (.buckets (.listBuckets client))))

(defmethod ig/init-key ::client [_ {:keys [host port access-key secret-key bucket-name]}]
  (let [endpoint (str "http://" host ":" port)
        credentials (AwsBasicCredentials/create access-key secret-key)
        s3config (-> (S3Configuration/builder)
                     (.pathStyleAccessEnabled true)
                     .build)
        ^S3Client client (-> ^S3ClientBuilder (S3Client/builder)
                             (doto
                              (.endpointOverride (URI. endpoint))
                               (.credentialsProvider (StaticCredentialsProvider/create credentials))
                               (.region (Region/of "us-east-1"))
                               (.serviceConfiguration ^S3Configuration s3config))
                             .build)]
    (when-not (bucket-exists? client bucket-name)
      (let [req (-> (CreateBucketRequest/builder)
                    (.bucket bucket-name)
                    .build)]
        (.createBucket client ^CreateBucketRequest req)))
    {:client client
     :bucket-name bucket-name}))

(defmethod ig/halt-key! ::client [_ {:keys [^S3Client client]}]
  (.close client))

(defmethod ig/init-key ::upload-file! [_ {:keys [^S3Client client bucket-name]}]
  (log/info "upload-file: client=" client " bucket-name=" bucket-name)
  (fn [key ^InputStream input-stream]
    (let [bytes (.readAllBytes input-stream)
          ^PutObjectRequest req (-> (PutObjectRequest/builder)
                                    (.bucket bucket-name)
                                    (.key key)
                                    .build)]
      (.putObject client req (RequestBody/fromBytes bytes)))))

(defmethod ig/init-key ::download-file [_ {:keys [^S3Client client bucket-name]}]
  (log/info "download-file: client=" client " bucket-name=" bucket-name)
  (fn [key]
    (let [^GetObjectRequest req (-> (GetObjectRequest/builder)
                                    (.bucket bucket-name)
                                    (.key key)
                                    .build)]
      (.getObject client req (ResponseTransformer/toInputStream)))))

(defmethod ig/init-key ::delete-file! [_ {:keys [^S3Client client bucket-name]}]
  (log/info "delete-file: client=" client " bucket-name=" bucket-name)
  (fn [key]
    (let [^DeleteObjectRequest req (-> (DeleteObjectRequest/builder)
                                       (.bucket bucket-name)
                                       (.key key)
                                       .build)]
      (.deleteObject client req))))

(defmethod ig/init-key ::file-exists? [_ {:keys [^S3Client client bucket-name]}]
  (log/info "file-exists?: client=" client " bucket-name=" bucket-name)
  (fn [key]
    (try
      (let [^HeadObjectRequest req (-> (HeadObjectRequest/builder)
                                       (.bucket bucket-name)
                                       (.key key)
                                       .build)]
        (.headObject client req)
        true)
      (catch Exception _ false))))
