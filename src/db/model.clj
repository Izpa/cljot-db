(ns db.model
  (:require
   [integrant.core :as ig])
  (:import
   (java.util
    UUID)))

(defmethod ig/init-key ::page-size [_ page-size]
  page-size)

(defmethod ig/init-key ::select-user [_ {:keys [execute!]}]
  (fn [user-id]
    (execute! {:select :*
               :from :tg-user
               :where [:= :id user-id]}
              true)))

(defmethod ig/init-key ::insert-user! [_ {:keys [execute!]}]
  (fn [chat]
    (execute! {:insert-into :tg-user
               :values [(select-keys chat
                                     [:id
                                      :username
                                      :last_name
                                      :first_name])]}
              true)))

(defmethod ig/init-key ::insert-file! [_ {:keys [execute!]}]
  (fn [values]
    (execute! {:insert-into :file
               :values [values]}
              true)))

(defmethod ig/init-key ::delete-file! [_ {:keys [execute!]}]
  (fn [file-id]
    (execute! {:delete-from :file
               :where [:= :id (UUID/fromString file-id)]}
              true)))

(defmethod ig/init-key ::select-file [_ {:keys [execute!]}]
  (fn [file-id]
    (execute! {:select :*
               :from :file
               :where [:= :id (UUID/fromString file-id)]}
              true)))

(defmethod ig/init-key ::list-files [_ {:keys [execute! page-size]}]
  (fn [page]
    (let [offset (* (dec page) page-size)]
      (execute! {:select [:id :name]
                 :from :file
                 :limit page-size
                 :offset offset}))))

(defmethod ig/init-key ::file-total-pages [_ {:keys [execute! page-size]}]
  (fn []
    (-> {:select [[[:count :*] :cnt]]
         :from :file}
        (execute! true)
        :cnt
        (/ page-size)
        Math/ceil
        int
        (max 1))))

(defmethod ig/init-key ::update-file-name! [_ {:keys [execute!]}]
  (fn [file-id new-name]
    (execute! {:update :file
               :set {:name new-name}
               :where [:= :id (UUID/fromString file-id)]}
              true)))
