(ns dialogue
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [telegrambot-lib.core :as tbot]
   [utils :refer [pformat]]
   [clojure.string :as str]))

(defmethod ig/init-key ::user-states [_ _]
  (atom {}))

(defmethod ig/init-key ::update-user-state [_ {:keys [user-states]}]
  (fn
    ([user-id state-name] (swap! user-states assoc user-id {:state-name state-name}))
    ([user-id state-name data] (swap! user-states assoc user-id {:state-name state-name
                                                                 :state-data data}))))

(defn telegram-send
  ([bot to-id main-content] (telegram-send bot to-id main-content {}))
  ([bot to-id main-content additional-content]
   (let [sent_message (tbot/send-message bot
                                         to-id
                                         main-content
                                         (merge {:parse_mode "HTML"}
                                                additional-content))]
     (log/info "Send message: "
               (pformat sent_message))
     sent_message)))

(defmethod ig/init-key ::telegram-send [_ {:keys [bot]}]
  (partial telegram-send bot))

(defmethod ig/init-key ::admin? [_ {:keys [admin-chat-ids]}]
  #(contains? admin-chat-ids %))

(defn command?
  [text]
  (when text (str/starts-with? text "/")))

(defmethod ig/init-key ::check-invite [_ {:keys [invite]}]
  (fn [msg-text]
    (when invite
      (let [[command received-invite & args] (str/split msg-text #"\s+")]
        (and (= command "/start")
             (= invite received-invite)
             (empty? args))))))

(defmethod ig/init-key ::user [_ {:keys [admin? select-user insert-user! check-invite]}]
  (fn [user-id text chat answer]
    (if-let [user (select-user user-id)]
      user
      (when (or (admin? user-id)
                (check-invite text))
        (insert-user! chat)
        (answer "Добро пожаловать!")))))

(defmethod ig/init-key ::default-state [_ {:keys [admin?]}]
  (fn [{{:keys [id]
         :as _chat} :chat
        :keys [_text]
        :as msg}
       _state-data
       answer]
    (let [_admin? (admin? id)]
      (answer msg))))

(defmethod ig/init-key ::process-msg [_ {:keys [telegram-send
                                                user-states
                                                states
                                                user]}]
  (fn [{{:keys [id]
         :as chat} :chat
        :keys [text]
        :as msg}]
    (let [answer (partial telegram-send id)
          user (user id text chat answer)]
      (log/info user)
      (if id
        (if user
          (let [{:keys [state-name state-data]} (get @user-states id {:state-name :default})]
                  ((get states state-name) msg state-data answer))
          (answer "This is private bot. Access denied."))
        (log/warn "strange message without chat-id: " (pformat msg))))))
