(ns model
  (:require
   [integrant.core :as ig])
  (:import (java.util UUID)))

(defmethod ig/init-key ::page-size [_ page-size]
  page-size)

(defmethod ig/init-key ::select-user [_ {:keys [db-execute!]}]
  (fn [user-id]
    (db-execute! {:select :*
                  :from :tg-user
                  :where [:= :id user-id]}
                 true)))

(defmethod ig/init-key ::insert-user! [_ {:keys [db-execute!]}]
  (fn [chat]
    (db-execute! {:insert-into :tg-user
                  :values [(select-keys chat
                                        [:id
                                         :username
                                         :last_name
                                         :first_name])]}
                 true)))

(defmethod ig/init-key ::insert-file! [_ {:keys [db-execute!]}]
  (fn [{:keys [original_chat_id original_message_id original_file_id storage_key name]}]
    (db-execute! {:insert-into :file
                  :values [{:original_chat_id original_chat_id
                            :original_message_id original_message_id
                            :original_file_id original_file_id
                            :storage_key storage_key
                            :name name}]}
                 true)))

(defmethod ig/init-key ::delete-file! [_ {:keys [db-execute!]}]
  (fn [file-id]
    (db-execute! {:delete-from :file
                  :where [:= :id file-id]}
                 true)))

(defmethod ig/init-key ::select-file [_ {:keys [db-execute!]}]
  (fn [file-id]
    (db-execute! {:select :*
                  :from :file
                  :where [:= :id (UUID/fromString file-id)]}
                 true)))

(defmethod ig/init-key ::list-files [_ {:keys [db-execute! page-size]}]
  (fn [page]
    (let [offset (* (dec page) page-size)]
      (db-execute! {:select [:id :name]
                    :from :file
                    :limit page-size
                    :offset offset}))))

(defmethod ig/init-key ::file-total-pages [_ {:keys [db-execute! page-size]}]
  (fn []
    (let [total (-> (db-execute! {:select [[[:count :*] :cnt]]
                                  :from :file}
                                 true)
                    :cnt)]
      (int (Math/ceil (/ total page-size))))))
