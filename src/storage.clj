(ns storage
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import [software.amazon.awssdk.auth.credentials AwsBasicCredentials StaticCredentialsProvider]
           [software.amazon.awssdk.regions Region]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model CreateBucketRequest PutObjectRequest GetObjectRequest DeleteObjectRequest HeadObjectRequest]
           [software.amazon.awssdk.core.sync RequestBody ResponseTransformer]
           [java.net URI]
           [java.io InputStream]))

(defn- bucket-exists? [^S3Client client bucket]
  (some #(= (.name %) bucket)
        (.buckets (.listBuckets client))))

(defmethod ig/init-key ::client [_ {:keys [host port access-key secret-key bucket-name]}]
  (let [endpoint (str "http://" host ":" port)
        credentials (AwsBasicCredentials/create access-key secret-key)
        client (-> (S3Client/builder)
                   (.endpointOverride (URI. endpoint))
                   (.credentialsProvider (StaticCredentialsProvider/create credentials))
                   (.region (Region/of "us-east-1"))
                   (.serviceConfiguration
                    (reify java.util.function.Consumer
                      (accept [_ builder]
                        (.pathStyleAccessEnabled builder true))))
                   (.build))]
    (when-not (bucket-exists? client bucket-name)
      (let [req (-> (CreateBucketRequest/builder)
                    (.bucket bucket-name)
                    .build)]
        (.createBucket client req)))
    {:client client
     :bucket-name bucket-name}))

(defmethod ig/halt-key! ::client [_ {:keys [client]}]
  (.close client))

(defmethod ig/init-key ::upload-file! [_ {:keys [client bucket-name]}]
  (log/info "upload-file: client=" client " bucket-name=" bucket-name)
  (fn [key ^InputStream input-stream]
    (let [bytes (.readAllBytes input-stream)
          req (-> (PutObjectRequest/builder)
                  (.bucket bucket-name)
                  (.key key)
                  .build)]
      (.putObject client req (RequestBody/fromBytes bytes)))))

(defmethod ig/init-key ::download-file [_ {:keys [client bucket-name]}]
  (log/info "download-file: client=" client " bucket-name=" bucket-name)
  (fn [key]
    (let [req (-> (GetObjectRequest/builder)
                  (.bucket bucket-name)
                  (.key key)
                  .build)]
      (.getObject client req (ResponseTransformer/toInputStream)))))

(defmethod ig/init-key ::delete-file! [_ {:keys [client bucket-name]}]
  (log/info "delete-file: client=" client " bucket-name=" bucket-name)
  (fn [key]
    (let [req (-> (DeleteObjectRequest/builder)
                  (.bucket bucket-name)
                  (.key key)
                  .build)]
      (.deleteObject client req))))

(defmethod ig/init-key ::file-exists? [_ {:keys [client bucket-name]}]
  (log/info "download-file: client=" client " bucket-name=" bucket-name)
  (fn [key]
    (try
      (let [req (-> (HeadObjectRequest/builder)
                    (.bucket bucket-name)
                    (.key key)
                    .build)]
        (.headObject client req)
        true)
      (catch Exception _ false))))
