pipeline {
    agent any

    stages {

        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/Naresh300898/website-monitoring-automation.git'
            }
        }

        stage('Setup Maven') {
            steps {
                sh '''
                if [ ! -d apache-maven-3.9.9 ]; then
                    wget https://downloads.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
                    tar -xvzf apache-maven-3.9.9-bin.tar.gz
                fi
                '''
            }
        }

        stage('Build') {
            steps {
                sh '''
                export MAVEN_HOME=$WORKSPACE/apache-maven-3.9.9
                export PATH=$MAVEN_HOME/bin:$PATH
                mvn clean install
                '''
            }
        }

        stage('Run Test') {
            steps {
                sh '''
                export MAVEN_HOME=$WORKSPACE/apache-maven-3.9.9
                export PATH=$MAVEN_HOME/bin:$PATH
                mvn test
                '''
            }
        }
    }
}
